package swarm.subsystems;

import swarm.infra.DroneConfig;
import swarm.infra.UDPHelper;
import swarm.infra.ZoneManager;
import swarm.main.SimulatorGUI;
import swarm.messages.DroneState;
import swarm.messages.FaultType;
import swarm.model.Position;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Scheduler subsystem.
 *
 * Responsibilities:
 * 1. Receive fire events from the FireIncidentSubsystem
 * 2. Decide which drone(s) to dispatch
 * 3. Send commands to the DroneSubsystem(s)
 * 4. Receive and log drone status updates
 * 5. Detect and handle drone faults via watchdog timer
 *
 */
public class Scheduler implements Runnable {

    // Watchdog: how long to wait for a STATUS before declaring the drone stuck.
    private static final long WATCHDOG_TIMEOUT_MS = 30_000;

    private final UDPHelper    udp;
    private final ZoneManager  zoneManager;

    // Mission queue entries: "zoneId:severity:faultType"
    private final Queue<String> missionQueue = new LinkedList<>();
    private final Map<Integer, Integer>     dronePorts             = new ConcurrentHashMap<>();
    private final Map<Integer, DroneState>  droneStates            = new ConcurrentHashMap<>();
    private final Map<Integer, Position>    dronePositions         = new ConcurrentHashMap<>();
    private final Map<Integer, Integer>     droneCompletedMissions = new ConcurrentHashMap<>();

    // Active mission per drone: droneId -> "zoneId:severity:faultType"
    private final Map<Integer, String> activeMissions = new ConcurrentHashMap<>();

    // Drones permanently disabled by a hard fault — never dispatched again
    private final Set<Integer> offlineDrones = ConcurrentHashMap.newKeySet();

    // Drones mid-recovery after SOFT_FAULT — their next IDLE is a reset, not a completion
    private final Set<Integer> recoveringDrones = ConcurrentHashMap.newKeySet();

    // Remaining agent (litres) per drone — decremented on DROPPING_AGENT, reset on REFILLING
    private final Map<Integer, Integer> droneAgentLiters = new ConcurrentHashMap<>();

    private final ScheduledExecutorService watchdogExecutor =
            Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "Watchdog");
                t.setDaemon(true);
                return t;
            });

    // Per-drone watchdog future; cancelled and rescheduled on every STATUS received
    private final Map<Integer, ScheduledFuture<?>> watchdogFutures = new ConcurrentHashMap<>();

    public Scheduler(UDPHelper udp, int numDrones, ZoneManager zoneManager) {
        this.udp         = udp;
        this.zoneManager = zoneManager;
        for (int i = 1; i <= numDrones; i++) {
            dronePorts.put(i, 6000 + i);
            droneStates.put(i, DroneState.IDLE);
            dronePositions.put(i, new Position(0, 0));
            droneCompletedMissions.put(i, 0);
            droneAgentLiters.put(i, DroneConfig.AGENT_CAPACITY_LITERS);
        }
    }

    @Override
    public void run() {
        System.out.println("[Scheduler] Listening on port 5000...");
        System.out.println("[Scheduler] Tracking " + dronePorts.size() + " drone(s)");
        try {
            while (true) {
                String message = udp.receive();
                handleMessage(message);
            }
        } catch (Exception e) {
            System.err.println("[Scheduler] Network error: " + e.getMessage());
        }
    }

    // =========================================================================
    // Message dispatch
    // =========================================================================

    private void handleMessage(String message) {
        String[] parts   = message.split(":");
        String   command = parts[0];

        switch (command) {
            case "FIRE"   -> handleFire(parts);
            case "STATUS" -> handleStatus(parts);
            default       -> System.err.println("[Scheduler] Unknown message: " + message);
        }
    }

    // ── FIRE ─────────────────────────────────────────────────────────────────

    private void handleFire(String[] parts) {
        // FIRE:zoneId:severity:eventType:faultType
        int    zoneId   = Integer.parseInt(parts[1]);
        String severity = parts[2];
        // parts[3] = eventType (not used by scheduler)
        String faultStr = (parts.length >= 5) ? parts[4] : FaultType.NONE.name();

        System.out.println("[Scheduler] Received fire event: Zone " + zoneId
                + " [" + severity + "] fault=" + faultStr);

        if (SimulatorGUI.instance != null) {
            SimulatorGUI.instance.incrementFire();
            SimulatorGUI.instance.setZoneOnFire(zoneId, severity);
            SimulatorGUI.instance.log("NEW INCIDENT: Zone " + zoneId
                    + " [" + severity + "] fault=" + faultStr);
        }

        synchronized (missionQueue) {
            missionQueue.add(zoneId + ":" + severity + ":" + faultStr);
        }
        tryDispatch();
    }

    // ── STATUS ───────────────────────────────────────────────────────────────

    private void handleStatus(String[] parts) {
        // STATUS:droneId:state:zoneId:posX:posY:faultType
        if (parts.length < 4) {
            System.err.println("[Scheduler] Malformed STATUS (too short): " + Arrays.toString(parts));
            return;
        }

        int       droneId = Integer.parseInt(parts[1]);
        DroneState state   = DroneState.valueOf(parts[2]);
        int       zoneId  = Integer.parseInt(parts[3]);

        // Position (fields 4 & 5) — where the drone currently is
        double posX = 0, posY = 0;
        if (parts.length >= 6) {
            posX = Double.parseDouble(parts[4]);
            posY = Double.parseDouble(parts[5]);
            dronePositions.put(droneId, new Position(posX, posY));
        }

        // Fault type (field 6)
        FaultType fault = FaultType.NONE;
        if (parts.length >= 7) {
            try {
                fault = FaultType.valueOf(parts[6]);
            } catch (IllegalArgumentException ex) {
                System.err.println("[Scheduler] Unknown fault type: " + parts[6]);
            }
        }

        // Reset watchdog for this drone (it's alive)
        resetWatchdog(droneId, state);

        droneStates.put(droneId, state);

        System.out.printf("[Drone %d] %-16s zone=%-3d fault=%s%n",
                droneId, state, zoneId, fault);

        if (SimulatorGUI.instance != null) {
            String zoneText = (zoneId != 0) ? " (Zone " + zoneId + ")" : "";
            SimulatorGUI.instance.log("[Drone " + droneId + "] " + state + zoneText
                    + (fault != FaultType.NONE ? " [" + fault + "]" : ""));

            double destX = posX, destY = posY;
            long   travelMs = 0;

            if (state == DroneState.EN_ROUTE && zoneId != 0) {
                Position zoneCenter = zoneManager.getZoneCenter(zoneId);
                if (zoneCenter != null) {
                    destX    = zoneCenter.x();
                    destY    = zoneCenter.y();
                    double dist = new Position(posX, posY).distanceTo(zoneCenter);
                    travelMs = DroneConfig.travelTimeMillis(dist) / 10;
                }
            } else if (state == DroneState.RETURNING) {
                destX    = DroneConfig.BASE_POSITION.x();
                destY    = DroneConfig.BASE_POSITION.y();
                double dist = new Position(posX, posY).distanceTo(DroneConfig.BASE_POSITION);
                travelMs = DroneConfig.travelTimeMillis(dist) / 10;
            } else if (state == DroneState.IDLE || state == DroneState.REFILLING) {
                SimulatorGUI.instance.snapDroneToBase(droneId);
            }

            SimulatorGUI.instance.updateDroneMovement(
                    droneId, state, zoneId, fault,
                    posX, posY, destX, destY, travelMs);
        }

        switch (state) {
            case DROPPING_AGENT -> {
                // Deduct agent used for this mission
                String activeMission = activeMissions.get(droneId);
                if (activeMission != null) {
                    try {
                        String sevStr = activeMission.split(":")[1];
                        int used = swarm.messages.Severity.valueOf(sevStr).litersRequired();
                        droneAgentLiters.merge(droneId, -used, Integer::sum);
                    } catch (Exception ignored) {}
                }
                // Push updated agent level to GUI
                if (SimulatorGUI.instance != null)
                    SimulatorGUI.instance.updateDroneAgent(droneId,
                            droneAgentLiters.getOrDefault(droneId, 0));

                if (SimulatorGUI.instance != null)
                    SimulatorGUI.instance.clearZone(zoneId);
            }
            case REFILLING -> {
                droneAgentLiters.put(droneId, DroneConfig.AGENT_CAPACITY_LITERS);
                // Push refilled level to GUI
                if (SimulatorGUI.instance != null)
                    SimulatorGUI.instance.updateDroneAgent(droneId, DroneConfig.AGENT_CAPACITY_LITERS);
            }
            case IDLE -> {
                activeMissions.remove(droneId);
                droneAgentLiters.put(droneId, DroneConfig.AGENT_CAPACITY_LITERS);
                // Push reset level to GUI
                if (SimulatorGUI.instance != null)
                    SimulatorGUI.instance.updateDroneAgent(droneId, DroneConfig.AGENT_CAPACITY_LITERS);

                if (recoveringDrones.remove(droneId)) {
                    System.out.println("[Scheduler] Drone " + droneId + " recovery complete — ready for dispatch.");
                    if (SimulatorGUI.instance != null)
                        SimulatorGUI.instance.log("[Scheduler] Drone " + droneId + " recovered and idle.");
                } else {
                    if (SimulatorGUI.instance != null)
                        SimulatorGUI.instance.decrementFire();
                    droneCompletedMissions.merge(droneId, 1, Integer::sum);
                }
                tryDispatch();
            }
            case HARD_FAULT -> handleHardFault(droneId, zoneId, fault);
            case SOFT_FAULT -> {
                recoveringDrones.add(droneId);
                requeueActiveMission(droneId);
                System.out.println("[Scheduler] Drone " + droneId
                        + " SOFT_FAULT (" + fault + ") — mission re-queued, awaiting self-recovery.");
                if (SimulatorGUI.instance != null)
                    SimulatorGUI.instance.markDroneFault(droneId, fault);
            }
        }

        // After any status update, check whether a RETURNING drone can intercept
        // a queued fire that it passes close enough to service.
        if (state == DroneState.RETURNING) {
            tryInterceptEnRoute(droneId, posX, posY);
        }
    }

    /** Hard fault: marks drone offline and re-queues its active mission. */
    private void handleHardFault(int droneId, int zoneId, FaultType fault) {
        offlineDrones.add(droneId);
        cancelWatchdog(droneId);

        System.out.println("[Scheduler] Drone " + droneId
                + " HARD FAULT (" + fault + ") — removing from pool.");

        if (SimulatorGUI.instance != null) {
            SimulatorGUI.instance.markDroneFault(droneId, fault);
            SimulatorGUI.instance.log("[Scheduler] Drone " + droneId
                    + " permanently offline due to " + fault);
        }

        requeueActiveMission(droneId);
    }

    /**
     * Watchdog timeout for a drone.
     * If the drone has a hard fault, treat as hard fault; otherwise treat as soft (DRONE_STUCK).
     */
    private void onWatchdogTimeout(int droneId) {
        DroneState currentState = droneStates.getOrDefault(droneId, DroneState.IDLE);

        System.out.println("[Scheduler] WATCHDOG timeout for Drone " + droneId
                + " (last state=" + currentState + ")");

        if (SimulatorGUI.instance != null) {
            SimulatorGUI.instance.log("[Scheduler] Watchdog expired for Drone " + droneId);
            SimulatorGUI.instance.markDroneFault(droneId, FaultType.DRONE_STUCK);
        }

        droneStates.put(droneId, DroneState.IDLE);
        requeueActiveMission(droneId);
        tryDispatch();
    }

    /** Re-queues the drone's active mission with fault cleared so it does not cascade. */
    private void requeueActiveMission(int droneId) {
        String mission = activeMissions.remove(droneId);
        if (mission != null) {
            String[] mp         = mission.split(":");
            String cleanMission = mp[0] + ":" + mp[1] + ":" + FaultType.NONE.name();
            System.out.println("[Scheduler] Re-queuing mission (fault cleared): " + cleanMission
                    + " (was assigned to Drone " + droneId + ")");
            if (SimulatorGUI.instance != null) {
                SimulatorGUI.instance.log("[Scheduler] Re-queuing (clean): " + cleanMission);
            }
            synchronized (missionQueue) {
                missionQueue.add(cleanMission);
            }
        }
    }

    /** Resets the watchdog timer for a drone, or cancels it if the drone is now idle or offline. */
    private void resetWatchdog(int droneId, DroneState newState) {
        cancelWatchdog(droneId);

        if (newState == DroneState.IDLE
                || newState == DroneState.HARD_FAULT
                || offlineDrones.contains(droneId)) {
            return;
        }

        ScheduledFuture<?> future = watchdogExecutor.schedule(
                () -> onWatchdogTimeout(droneId),
                WATCHDOG_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
        );
        watchdogFutures.put(droneId, future);
    }

    private void cancelWatchdog(int droneId) {
        ScheduledFuture<?> existing = watchdogFutures.remove(droneId);
        if (existing != null) existing.cancel(false);
    }

    /** Checks if a returning drone can intercept a queued fire en route to base. */
    private static final double INTERCEPT_RADIUS_M = 400.0;

    private void tryInterceptEnRoute(int droneId, double curX, double curY) {
        if (offlineDrones.contains(droneId)) return;

        Position current = new Position(curX, curY);
        Position base    = DroneConfig.BASE_POSITION;

        synchronized (missionQueue) {
            if (missionQueue.isEmpty()) return;

            int    agentLeft    = droneAgentLiters.getOrDefault(droneId, 0);
            String bestMission  = null;
            double bestDist     = Double.MAX_VALUE;

            for (String mission : missionQueue) {
                String[] mp = mission.split(":");
                int zoneId  = Integer.parseInt(mp[0]);
                int required;
                try {
                    required = swarm.messages.Severity.valueOf(mp[1]).litersRequired();
                } catch (Exception e) { continue; }

                if (agentLeft < required) continue;   // not enough agent

                Position zoneCenter = zoneManager.getZoneCenter(zoneId);
                if (zoneCenter == null) continue;

                double directDist  = current.distanceTo(base);
                double detourDist  = current.distanceTo(zoneCenter) + zoneCenter.distanceTo(base);
                double detourExtra = detourDist - directDist;

                if (detourExtra <= INTERCEPT_RADIUS_M) {
                    double d = current.distanceTo(zoneCenter);
                    if (d < bestDist) {
                        bestDist    = d;
                        bestMission = mission;
                    }
                }
            }

            if (bestMission == null) return;

            missionQueue.remove(bestMission);
            String[] mp      = bestMission.split(":");
            int   targetZone = Integer.parseInt(mp[0]);
            String severity  = mp[1];
            String faultStr  = FaultType.NONE.name();

            droneStates.put(droneId, DroneState.EN_ROUTE);
            activeMissions.put(droneId, bestMission);

            try {
                String cmd = "CMD:" + targetZone + ":" + severity + ":" + faultStr;
                udp.send(cmd, dronePorts.get(droneId));
                System.out.println("[Scheduler] Drone " + droneId
                        + " intercepting Zone " + targetZone
                        + " [" + severity + "] while returning (agent=" + agentLeft + "L)");
                if (SimulatorGUI.instance != null) {
                    SimulatorGUI.instance.log("[Scheduler] Drone " + droneId
                            + " intercepts Zone " + targetZone + " en route to base");
                    SimulatorGUI.instance.setZoneDrone(targetZone, droneId);
                }
                resetWatchdog(droneId, DroneState.EN_ROUTE);
            } catch (Exception e) {
                System.err.println("[Scheduler] Intercept dispatch failed: " + e.getMessage());
                missionQueue.add(bestMission);
                activeMissions.remove(droneId);
                droneStates.put(droneId, DroneState.RETURNING);
            }
        }
    }

    /** Finds an idle drone with sufficient agent and dispatches it to the next queued mission. */
    private void tryDispatch() {
        synchronized (missionQueue) {
            if (missionQueue.isEmpty()) return;

            // Peek at the target zone to calculate distances
            String nextMission  = missionQueue.peek();
            String[] mParts     = nextMission.split(":");
            int    targetZoneId = Integer.parseInt(mParts[0]);
            Position targetPos  = zoneManager.getZoneCenter(targetZoneId);

            if (targetPos == null) {
                System.err.println("[Scheduler] Zone " + targetZoneId + " not found, discarding mission.");
                missionQueue.poll();
                return;
            }

            // Resolve how many liters this mission requires up front so we
            // can skip drones that don't have enough agent in the loop below.
            int required;
            try {
                required = swarm.messages.Severity.valueOf(mParts[1]).litersRequired();
            } catch (Exception e) {
                System.err.println("[Scheduler] Unknown severity in mission: " + nextMission);
                missionQueue.poll();
                return;
            }

            // Find closest idle drone (excluding hard-faulted and under-stocked ones),
            // break ties by fewest completed missions.
            Integer bestDroneId   = null;
            double  bestDistance  = Double.MAX_VALUE;
            int     bestCompleted = Integer.MAX_VALUE;

            for (Map.Entry<Integer, DroneState> entry : droneStates.entrySet()) {
                int id = entry.getKey();
                if (offlineDrones.contains(id)) continue;
                if (entry.getValue() != DroneState.IDLE) continue;

                // Skip drones that don't carry enough agent for this mission
                if (droneAgentLiters.getOrDefault(id, 0) < required) continue;

                Position dronePos  = dronePositions.get(id);
                double   dist      = dronePos.distanceTo(targetPos);
                int      completed = droneCompletedMissions.getOrDefault(id, 0);

                if (dist < bestDistance || (dist == bestDistance && completed < bestCompleted)) {
                    bestDroneId   = id;
                    bestDistance  = dist;
                    bestCompleted = completed;
                }
            }

            if (bestDroneId == null) {
                System.out.println("[Scheduler] No idle drones with sufficient agent — mission queued.");
                return;
            }

            String mission  = missionQueue.poll();
            String[] mps    = mission.split(":");
            String severity = mps[1];
            String faultStr = (mps.length >= 3) ? mps[2] : FaultType.NONE.name();
            int    dronePort = dronePorts.get(bestDroneId);

            droneStates.put(bestDroneId, DroneState.EN_ROUTE);
            activeMissions.put(bestDroneId, mission);

            try {
                // CMD:zoneId:severity:faultType
                String cmdMessage = "CMD:" + targetZoneId + ":" + severity + ":" + faultStr;
                udp.send(cmdMessage, dronePort);
                System.out.println("[Scheduler] Dispatched Drone " + bestDroneId
                        + " → Zone " + targetZoneId
                        + " [" + severity + "] fault=" + faultStr);

                if (SimulatorGUI.instance != null) {
                    SimulatorGUI.instance.setZoneDrone(targetZoneId, bestDroneId);
                }

                resetWatchdog(bestDroneId, DroneState.EN_ROUTE);

            } catch (Exception e) {
                System.err.println("[Scheduler] Failed to dispatch Drone " + bestDroneId
                        + ": " + e.getMessage());
                missionQueue.add(mission);
                activeMissions.remove(bestDroneId);
                droneStates.put(bestDroneId, DroneState.IDLE);
            }
        }
    }

    // =========================================================================
    // Standalone entry point
    // =========================================================================

    public static void main(String[] args) {
        try {
            int numDrones = args.length > 0 ? Integer.parseInt(args[0]) : 1;
            ZoneManager zm  = new ZoneManager("sample_zone_file.csv");
            UDPHelper   udp = new UDPHelper(5000);
            new Scheduler(udp, numDrones, zm).run();
        } catch (Exception e) {
            System.err.println("Failed to start Scheduler");
            e.printStackTrace();
        }
    }
}
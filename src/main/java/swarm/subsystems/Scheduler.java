package swarm.subsystems;

import swarm.infra.UDPHelper;
import swarm.infra.ZoneManager;
import swarm.main.SimulatorGUI;
import swarm.messages.DroneState;
import swarm.messages.FaultType;
import swarm.model.Position;

import java.io.IOException;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scheduler subsystem.
 *
 * Responsibilities:
 * 1. Receive fire events from the FireIncidentSubsystem
 * 2. Decide which drone(s) to dispatch
 * 3. Send commands to the DroneSubsystem(s)
 * 4. Receive and log drone status updates
 *
 */
public class Scheduler implements Runnable {

    private final UDPHelper udp;
    private final ZoneManager zoneManager;
    private final Queue<String> missionQueue = new LinkedList<>();
    private final Map<Integer, Integer> dronePorts = new ConcurrentHashMap<>();
    private final Map<Integer, DroneState> droneStates = new ConcurrentHashMap<>();
    private final Map<Integer, Position> dronePositions = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> droneCompletedMissions = new ConcurrentHashMap<>();

    public Scheduler(UDPHelper udp, int numDrones, ZoneManager zoneManager) {
        this.udp = udp;
        this.zoneManager = zoneManager;
        for (int i = 1; i <= numDrones; i++) {
            dronePorts.put(i, 6000 + i);
            droneStates.put(i, DroneState.IDLE);
            dronePositions.put(i, new Position(0, 0)); // base
            droneCompletedMissions.put(i, 0);
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

    private void handleMessage(String message) {
        String[] parts = message.split(":");
        String command = parts[0];

        if (command.equals("FIRE")) {
            int zoneId = Integer.parseInt(parts[1]);
            String severity = parts[2];

            System.out.println("[Scheduler] Received fire event: Zone " + zoneId);

            if (SimulatorGUI.instance != null) {
                SimulatorGUI.instance.incrementFire();
                SimulatorGUI.instance.setZoneOnFire(zoneId, severity);
                SimulatorGUI.instance.log("NEW INCIDENT: Zone " + zoneId + " [" + severity + "]");
            }

            synchronized (missionQueue) {
                missionQueue.add(zoneId + ":" + severity);
            }
            tryDispatch();

        } else if (command.equals("STATUS")) {
            int droneId = Integer.parseInt(parts[1]);
            DroneState state = DroneState.valueOf(parts[2]);
            int zoneId = Integer.parseInt(parts[3]);

            // Parse position from extended STATUS message
            if (parts.length >= 6) {
                double posX = Double.parseDouble(parts[4]);
                double posY = Double.parseDouble(parts[5]);
                dronePositions.put(droneId, new Position(posX, posY));
            }

            FaultType fault = (parts.length >= 5) ? FaultType.valueOf(parts[4]) : FaultType.NONE;

            droneStates.put(droneId, state);

            System.out.println("[Drone " + droneId + "] is now " + state + " (Zone " + zoneId + ")");

            if (SimulatorGUI.instance != null) {
                String zoneText = (zoneId != 0) ? " (Zone " + zoneId + ")" : "";
                SimulatorGUI.instance.updateDroneInfo(droneId, state, zoneId);
                SimulatorGUI.instance.log("[Drone " + droneId + "] is now " + state + zoneText);

                if (state == DroneState.DROPPING_AGENT) {
                    SimulatorGUI.instance.clearZone(zoneId);
                }
                if (state == DroneState.IDLE) {
                    SimulatorGUI.instance.decrementFire();
                    droneCompletedMissions.merge(droneId, 1, Integer::sum);
                }
            }

            if (state == DroneState.IDLE) {
                tryDispatch();
            }
        }
    }

    /**
     * Finds an idle drone and dispatches it to the next queued mission.
     */
    private void tryDispatch() {
        synchronized (missionQueue) {
            if (missionQueue.isEmpty()) return;

            // Peek at the target zone to calculate distances
            String nextMission = missionQueue.peek();
            int targetZoneId = Integer.parseInt(nextMission.split(":")[0]);
            Position targetPos = zoneManager.getZoneCenter(targetZoneId);

            if (targetPos == null) {
                missionQueue.poll(); // discard invalid mission
                return;
            }

            // Find closest idle drone, break ties by fewest completed missions
            Integer bestDroneId = null;
            double bestDistance = Double.MAX_VALUE;
            int bestCompleted = Integer.MAX_VALUE;

            for (Map.Entry<Integer, DroneState> entry : droneStates.entrySet()) {
                if (entry.getValue() == DroneState.IDLE) {
                    int id = entry.getKey();
                    Position dronePos = dronePositions.get(id);
                    double dist = dronePos.distanceTo(targetPos);
                    int completed = droneCompletedMissions.getOrDefault(id, 0);

                    if (dist < bestDistance || (dist == bestDistance && completed < bestCompleted)) {
                        bestDroneId = id;
                        bestDistance = dist;
                        bestCompleted = completed;
                    }
                }
            }

            if (bestDroneId == null) {
                System.out.println("[Scheduler] No idle drones, mission queued.");
                return;
            }

            String mission = missionQueue.poll();
            String severity = mission.split(":")[1];
            int dronePort = dronePorts.get(bestDroneId);
            droneStates.put(bestDroneId, DroneState.EN_ROUTE);

            try {
                String cmdMessage = "CMD:" + mission;
                udp.send(cmdMessage, dronePort);
                System.out.println("[Scheduler] Dispatched Drone " + bestDroneId + " to mission " + mission);

                if (SimulatorGUI.instance != null) {
                    SimulatorGUI.instance.setZoneDrone(targetZoneId, bestDroneId);
                }
            } catch (Exception e) {
                System.err.println("[Scheduler] Failed to dispatch Drone " + bestDroneId);
                missionQueue.add(mission);
                droneStates.put(bestDroneId, DroneState.IDLE);
            }
        }
    }

    public static void main(String[] args) {
        try {
            int numDrones = args.length > 0 ? Integer.parseInt(args[0]) : 1;
            ZoneManager zm = new ZoneManager("sample_zone_file.csv");
            UDPHelper udp = new UDPHelper(5000);
            new Scheduler(udp, numDrones, zm).run();
        } catch (Exception e) {
            System.err.println("Failed to start Scheduler");
            e.printStackTrace();
        }
    }
}
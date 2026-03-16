package swarm.subsystems;

import swarm.infra.UDPHelper;
import swarm.main.SimulatorGUI;
import swarm.messages.DroneState;

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
 * Iteration 3: supports multiple drones with balanced scheduling.
 */
public class Scheduler implements Runnable {

    private final UDPHelper udp;
    private final Queue<String> missionQueue = new LinkedList<>(); // "zoneId:severity"
    private final Map<Integer, Integer> dronePorts = new ConcurrentHashMap<>();   // droneId → port
    private final Map<Integer, DroneState> droneStates = new ConcurrentHashMap<>(); // droneId → state

    public Scheduler(UDPHelper udp, int numDrones) {
        this.udp = udp;
        for (int i = 1; i <= numDrones; i++) {
            dronePorts.put(i, 6000 + i);
            droneStates.put(i, DroneState.IDLE);
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
                SimulatorGUI.instance.setZoneOnFire(zoneId);
                SimulatorGUI.instance.log("NEW INCIDENT: Zone " + zoneId);
            }

            // Queue the mission and attempt dispatch
            synchronized (missionQueue) {
                missionQueue.add(zoneId + ":" + severity);
            }
            tryDispatch();

        } else if (command.equals("STATUS")) {
            int droneId = Integer.parseInt(parts[1]);
            DroneState state = DroneState.valueOf(parts[2]);
            int zoneId = Integer.parseInt(parts[3]);

            // Update drone state tracking
            droneStates.put(droneId, state);

            System.out.println("[Drone " + droneId + "] is now " + state + " (Zone " + zoneId + ")");

            if (SimulatorGUI.instance != null) {
                String zoneText = (zoneId != 0) ? " (Zone " + zoneId + ")" : "";
                SimulatorGUI.instance.log("[Drone " + droneId + "] is now " + state + zoneText);
                SimulatorGUI.instance.updateDroneState(droneId, state);

                if (state == DroneState.DROPPING_AGENT) {
                    SimulatorGUI.instance.clearZone(zoneId);
                }
                if (state == DroneState.IDLE) {
                    SimulatorGUI.instance.decrementFire();
                }
            }

            // If drone just became idle, try to assign next mission
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

            // Find first idle drone
            Integer bestDroneId = null;
            for (Map.Entry<Integer, DroneState> entry : droneStates.entrySet()) {
                if (entry.getValue() == DroneState.IDLE) {
                    bestDroneId = entry.getKey();
                    break;
                }
            }

            if (bestDroneId == null) {
                System.out.println("[Scheduler] No idle drones, mission queued.");
                return;
            }

            String mission = missionQueue.poll();
            int dronePort = dronePorts.get(bestDroneId);
            droneStates.put(bestDroneId, DroneState.EN_ROUTE); // Mark busy immediately

            try {
                String cmdMessage = "CMD:" + mission;
                udp.send(cmdMessage, dronePort);
                System.out.println("[Scheduler] Dispatched Drone " + bestDroneId + " to mission " + mission);
            } catch (Exception e) {
                System.err.println("[Scheduler] Failed to dispatch Drone " + bestDroneId);
                // Put mission back in queue
                missionQueue.add(mission);
                droneStates.put(bestDroneId, DroneState.IDLE);
            }
        }
    }

    public static void main(String[] args) {
        try {
            int numDrones = args.length > 0 ? Integer.parseInt(args[0]) : 1;
            UDPHelper udp = new UDPHelper(5000);
            new Scheduler(udp, numDrones).run();
        } catch (Exception e) {
            System.err.println("Failed to start Scheduler");
            e.printStackTrace();
        }
    }
}
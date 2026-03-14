package swarm.subsystems;

import swarm.infra.DroneConfig;
import swarm.infra.UDPHelper;
import swarm.infra.ZoneManager;
import swarm.main.SimulatorGUI;
import swarm.messages.DroneCommand;
import swarm.messages.DroneState;
import swarm.messages.DroneStatus;
import swarm.messages.Severity;
import swarm.model.Position;


/**
 * Drone subsystem.
 *
 * Responsibilities (Iteration 1):
 * 1. Consults scheduler
 * 2. Simulates drone en route
 * 3. simul.ated drone putting out fire
 *
 * logic is intentionally minimal for Iteration 1.
 */
public class DroneSubsystem implements Runnable{
    private final UDPHelper udp;
    private final int droneId;
    private final ZoneManager zoneManager;
    private int currentAgent;
    private Position currentPosition;

    public DroneSubsystem(UDPHelper udp, int droneId, ZoneManager zoneManager) {
        this.udp = udp;
        this.droneId = droneId;
        this.zoneManager = zoneManager;
        this.currentAgent = DroneConfig.AGENT_CAPACITY_LITERS;
        this.currentPosition = DroneConfig.BASE_POSITION;
    }

    @Override
    public void run() {
        System.out.println("[Drone] " + droneId + "] Listening on port 6000...");
        // thread to handle drone commands
        new Thread(this::processMissions, "Drone-" + droneId + "-Processor").start();
    }

    /**
     * Checks for drone commands and dispatches a drone
     */
    private void processMissions() {
        while (true) {
            try {
                // Receive command from Scheduler
                String message = udp.receive();
                String[] parts = message.split(":");

                if (parts[0].equals("CMD")) {
                    int zoneId = Integer.parseInt(parts[1]);
                    Severity severity = Severity.valueOf(parts[2]);

                    Position target = zoneManager.getZoneCenter(zoneId);
                    if (target == null) {
                        System.err.println("[DRONE FAILURE] Zone " + zoneId + " not found.");
                        continue;
                    }

                    // EN_ROUTE: Calculate flight time based on distance
                    reportStatus(DroneState.EN_ROUTE, zoneId);
                    long flightTime = DroneConfig.travelTimeMillis(currentPosition.distanceTo(target));
                    Thread.sleep(flightTime / 10);
                    currentPosition = target;

                    // DROPPING_AGENT: Time = door cycle + flow rate
                    reportStatus(DroneState.DROPPING_AGENT, zoneId);
                    long dropTime = DroneConfig.dropTimeMillis(severity.litersRequired())
                            + DroneConfig.doorOpenCloseMillis();
                    Thread.sleep(dropTime / 10);
                    currentAgent -= severity.litersRequired();

                    // RETURNING: Back to base to refill
                    reportStatus(DroneState.RETURNING, zoneId);
                    long returnTime = DroneConfig.travelTimeMillis(currentPosition.distanceTo(DroneConfig.BASE_POSITION));
                    Thread.sleep(returnTime / 10);

                    // REFILLING
                    currentPosition = DroneConfig.BASE_POSITION;
                    currentAgent = DroneConfig.AGENT_CAPACITY_LITERS; // Refilled
                    reportStatus(DroneState.REFILLING, zoneId);
                    Thread.sleep(200); // Small delay simulating refill

                    // IDLE
                    reportStatus(DroneState.IDLE, null);
                }
            } catch (Exception e) {
                System.err.println("[DRONE] Network error or bad packet: " + e.getMessage());
            }
        }
    }

    private void reportStatus(DroneState state, Integer zoneId) {
        try{
            // Serialize state into string format
            // If zoneId is null, use 0 as placeholder
            String zoneIdString = (zoneId != null) ? String.valueOf(zoneId) : "0";
            String statusMessage = "STATUS:" + droneId + ":" + state.name() + ":" + zoneIdString;

            // Send back to Scheduler on Port 5000
            udp.send(statusMessage, 5000);

            if (SimulatorGUI.instance != null) {
                SimulatorGUI.instance.log("[Drone " + droneId + "] is now " + state +
                        (zoneId != null ? " (Zone " + zoneId + ")" : ""));
            }
        } catch (Exception e){
            System.err.println("[Drone] Failed to send status update.");
        }
    }
    public static void main(String[] args){
        System.out.println("Starting Drone subsystem");
        try{
            swarm.infra.ZoneManager zm = new swarm.infra.ZoneManager("sample_zone_file.csv");

            // connect to network
            swarm.infra.UDPHelper udp = new swarm.infra.UDPHelper(6000);

            // Start subsystem
            DroneSubsystem drone = new DroneSubsystem(udp, 1, zm);
            drone.run();
        } catch (Exception e){
            System.err.println("Failed to start Drone subsystem");
            e.printStackTrace();
        }
    }
}
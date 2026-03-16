package swarm.subsystems;

import swarm.infra.DroneConfig;
import swarm.infra.UDPHelper;
import swarm.infra.ZoneManager;
import swarm.main.SimulatorGUI;
import swarm.messages.DroneState;
import swarm.messages.Severity;
import swarm.model.Position;

/**
 * Drone subsystem.
 *
 * Responsibilities:
 * 1. Receives commands from the Scheduler
 * 2. Simulates drone flight, agent drop, and return
 * 3. Reports status back to the Scheduler
 *
 * Iteration 3: each drone runs as a separate process with its own port.
 */
public class DroneSubsystem implements Runnable {
    private final UDPHelper udp;
    private final int droneId;
    private final int port;
    private final ZoneManager zoneManager;
    private int currentAgent;
    private Position currentPosition;

    public DroneSubsystem(UDPHelper udp, int droneId, int port, ZoneManager zoneManager) {
        this.udp = udp;
        this.droneId = droneId;
        this.port = port;
        this.zoneManager = zoneManager;
        this.currentAgent = DroneConfig.AGENT_CAPACITY_LITERS;
        this.currentPosition = DroneConfig.BASE_POSITION;
    }

    @Override
    public void run() {
        System.out.println("[Drone " + droneId + "] Listening on port " + port + "...");
        new Thread(this::processMissions, "Drone-" + droneId + "-Processor").start();
    }

    private void processMissions() {
        while (true) {
            try {
                String message = udp.receive();
                String[] parts = message.split(":");

                if (parts[0].equals("CMD")) {
                    int zoneId = Integer.parseInt(parts[1]);
                    Severity severity = Severity.valueOf(parts[2]);

                    Position target = zoneManager.getZoneCenter(zoneId);
                    if (target == null) {
                        System.err.println("[Drone " + droneId + "] Zone " + zoneId + " not found.");
                        continue;
                    }

                    // EN_ROUTE
                    reportStatus(DroneState.EN_ROUTE, zoneId);
                    long flightTime = DroneConfig.travelTimeMillis(currentPosition.distanceTo(target));
                    Thread.sleep(flightTime / 10);
                    currentPosition = target;

                    // ARRIVED
                    reportStatus(DroneState.ARRIVED, zoneId);

                    // DROPPING_AGENT
                    reportStatus(DroneState.DROPPING_AGENT, zoneId);
                    long dropTime = DroneConfig.dropTimeMillis(severity.litersRequired())
                            + DroneConfig.doorOpenCloseMillis();
                    Thread.sleep(dropTime / 10);
                    currentAgent -= severity.litersRequired();

                    // RETURNING
                    reportStatus(DroneState.RETURNING, zoneId);
                    long returnTime = DroneConfig.travelTimeMillis(currentPosition.distanceTo(DroneConfig.BASE_POSITION));
                    Thread.sleep(returnTime / 10);

                    // REFILLING
                    currentPosition = DroneConfig.BASE_POSITION;
                    currentAgent = DroneConfig.AGENT_CAPACITY_LITERS;
                    reportStatus(DroneState.REFILLING, zoneId);
                    Thread.sleep(200);

                    // IDLE
                    reportStatus(DroneState.IDLE, null);
                }
            } catch (Exception e) {
                System.err.println("[Drone " + droneId + "] Error: " + e.getMessage());
            }
        }
    }

    private void reportStatus(DroneState state, Integer zoneId) {
        try {
            String zoneIdString = (zoneId != null) ? String.valueOf(zoneId) : "0";
            // Append position so Scheduler knows where each drone is
            String statusMessage = "STATUS:" + droneId + ":" + state.name() + ":" + zoneIdString
                    + ":" + currentPosition.x() + ":" + currentPosition.y();
            udp.send(statusMessage, 5000);

            if (SimulatorGUI.instance != null) {
                SimulatorGUI.instance.log("[Drone " + droneId + "] is now " + state +
                        (zoneId != null ? " (Zone " + zoneId + ")" : ""));
            }
        } catch (Exception e) {
            System.err.println("[Drone " + droneId + "] Failed to send status.");
        }
    }

    public static void main(String[] args) {
        try {
            int droneId = args.length > 0 ? Integer.parseInt(args[0]) : 1;
            int port = 6000 + droneId;

            ZoneManager zm = new ZoneManager("sample_zone_file.csv");
            UDPHelper udp = new UDPHelper(port);
            new DroneSubsystem(udp, droneId, port, zm).run();
        } catch (Exception e) {
            System.err.println("Failed to start Drone " + (args.length > 0 ? args[0] : "1"));
            e.printStackTrace();
        }
    }
}
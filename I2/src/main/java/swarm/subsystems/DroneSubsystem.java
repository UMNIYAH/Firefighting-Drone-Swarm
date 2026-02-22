package swarm.subsystems;

import swarm.infra.DroneConfig;
import swarm.infra.MessageBus;
import swarm.infra.ZoneManager;
import swarm.messages.DroneCommand;
import swarm.messages.DroneState;
import swarm.messages.DroneStatus;
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
    private final MessageBus bus;
    private final int droneId;
    private final ZoneManager zoneManager;
    private int currentAgent;
    private Position currentPosition;

    public DroneSubsystem(MessageBus bus, int droneId, ZoneManager zoneManager) {
        this.bus = bus;
        this.droneId = droneId;
        this.zoneManager = zoneManager;
        this.currentAgent = DroneConfig.AGENT_CAPACITY_LITERS;
        this.currentPosition = DroneConfig.BASE_POSITION;
    }

    @Override
    public void run() {
        // thread to handle drone commands
        new Thread(this::processMissions, "Drone-" + droneId + "-Processor").start();
    }

    /**
     * Checks for drone commands and dispatches a drone
     */
    private void processMissions() {
        try {
            while (true) {
                // 1. IDLE: Wait for work
                reportStatus(DroneState.IDLE, null);
                DroneCommand cmd = bus.droneCommands.take();

                // Get the target zone center safely
                Position target = zoneManager.getZoneCenter(cmd.zoneId());
                if (target == null) {
                    System.err.println("[Drone " + droneId + "] ERROR: Zone " + cmd.zoneId() + " not found!");
                    continue; // skip this mission
                }

                // 2. EN_ROUTE: Calculate flight time based on distance
                reportStatus(DroneState.EN_ROUTE, cmd.zoneId());
                long flightTime = DroneConfig.travelTimeMillis(currentPosition.distanceTo(target));
                Thread.sleep(flightTime);
                currentPosition = target;

                // 3. DROPPING_AGENT: Time = door cycle + flow rate
                reportStatus(DroneState.DROPPING_AGENT, cmd.zoneId());
                long dropTime = DroneConfig.dropTimeMillis(cmd.severity().litersRequired())
                        + DroneConfig.doorOpenCloseMillis();
                Thread.sleep(dropTime);
                currentAgent -= cmd.severity().litersRequired();

                // 4. RETURNING: Back to base to refill (Iteration 2 logic)
                reportStatus(DroneState.RETURNING, null);
                Thread.sleep(DroneConfig.travelTimeMillis(currentPosition.distanceTo(DroneConfig.BASE_POSITION)));

                // 5. REFILLING
                currentPosition = DroneConfig.BASE_POSITION;
                currentAgent = DroneConfig.AGENT_CAPACITY_LITERS; // Refilled
                reportStatus(DroneState.REFILLING, null);
                Thread.sleep(1000); // Small delay simulating refill
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void reportStatus(DroneState state, Integer zoneId) throws InterruptedException{
        bus.droneStatuses.put(new DroneStatus(droneId, state, zoneId, currentAgent));
    }
}
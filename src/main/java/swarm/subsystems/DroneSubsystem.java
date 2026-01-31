package swarm.subsystems;

import swarm.infra.MessageBus;
import swarm.messages.DroneCommand;
import swarm.messages.DroneStatus;


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
    private final int droneID;

    public DroneSubsystem(MessageBus bus, int droneId) {
        this.bus = bus;
        this.droneID = droneId;
    }

    @Override
    public void run() {
        // thread to handle drone commands
        Thread commandHandler = new Thread(this::handleCommand,"Drone-"+droneID+"-CommandHandler");

        commandHandler.start();
    }

    /**
     * Checks for drone commands and dispatches a drone
     */
    private void handleCommand() {
        try {
            while (true) {
                DroneCommand command = bus.droneCommands.take();
                System.out.println("[Drone " + droneID+ "] Received command: " + command);

                // Simulate drone en route
                bus.droneStatuses.put(new DroneStatus(droneID, "EN_ROUTE", command.zoneId()));

                //Delay to simulate travel time
                Thread.sleep(500);

                // Simulate drone arrived and extinguished fire
                bus.droneStatuses.put(new DroneStatus(droneID, "ARRIVED", command.zoneId()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

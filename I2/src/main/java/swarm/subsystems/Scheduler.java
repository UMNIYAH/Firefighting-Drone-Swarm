package swarm.subsystems;

import swarm.infra.MessageBus;
import swarm.messages.DroneCommand;
import swarm.messages.DroneStatus;
import swarm.messages.FireEvent;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduler subsystem.
 *
 * Responsibilities (Iteration 1):
 * 1. Receive fire events from the FireIncidentSubsystem
 * 2. Decide which drone to dispatch (single-drone assumption)
 * 3. Send commands to the DroneSubsystem
 * 4. Receive and log drone status updates
 *
 * Scheduling logic is intentionally minimal for Iteration 1.
 */
public class Scheduler implements Runnable {

    private final MessageBus bus;
    private final int assignedDroneId = 1; // Single-drone assumption
    private final AtomicBoolean droneBusy = new AtomicBoolean(false);

    public Scheduler(MessageBus bus) {
        this.bus = bus;
    }

    @Override
    public void run() {
        // Thread to consume fire events
        Thread fireEventHandler = new Thread(this::handleFireEvents, "Scheduler-FireHandler");

        // Thread to consume drone status updates
        Thread droneStatusHandler = new Thread(this::handleDroneStatuses, "Scheduler-StatusHandler");

        fireEventHandler.start();
        droneStatusHandler.start();
    }

    /**
     * Consumes FireEvent messages and dispatches a drone.
     */
    private void handleFireEvents() {
        try {
            while (true) {
                FireEvent event = bus.fireEvents.take();
                System.out.println("[Scheduler] Received fire event: " + event);

                // Iteration 1: single drone, no queueing
                if (!droneBusy.get()) {
                    dispatchDrone(event);
                } else {
                    System.out.println("[Scheduler] Drone busy, event ignored (Iteration 1 simplification)");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Sends a DroneCommand to the DroneSubsystem.
     */
    private void dispatchDrone(FireEvent event) throws InterruptedException {
        DroneCommand command = new DroneCommand(
                assignedDroneId,
                event.zoneId(),
                event.severity()
        );

        droneBusy.set(true);
        bus.droneCommands.put(command);

        System.out.println("[Scheduler] Dispatched drone " + assignedDroneId +
                " to zone " + event.zoneId());
    }

    /**
     * Consumes DroneStatus messages from the DroneSubsystem.
     */
    private void handleDroneStatuses() {
        try {
            while (true) {
                DroneStatus status = bus.droneStatuses.take();
                System.out.println("[Scheduler] Received drone status: " + status);

                // Iteration 1: consider drone free once it reports ARRIVED
                if ("ARRIVED".equalsIgnoreCase(status.state())) {
                    droneBusy.set(false);
                    System.out.println("[Scheduler] Drone " + status.droneId() + " is now available");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
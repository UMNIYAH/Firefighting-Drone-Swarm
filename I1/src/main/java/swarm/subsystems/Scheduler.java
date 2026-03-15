package swarm.subsystems;

import swarm.infra.MessageBus;
import swarm.messages.DroneCommand;
import swarm.messages.DroneStatus;
import swarm.messages.FireEvent;

import java.util.LinkedList;
import java.util.Queue;
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
    private final Queue<FireEvent> missionQueue = new LinkedList<>();
    private boolean droneBusy = false;
    private final int droneId = 1;

    public Scheduler(MessageBus bus) {
        this.bus = bus;
    }

    @Override
    public void run() {
        // Thread handling fire events
        new Thread(this::handleFireEvents, "Scheduler-FireHandler").start();
        // Thread handling drone status updates
        new Thread(this::handleDroneStatuses, "Scheduler-DroneStatusHandler").start();
    }

    /**
     * Consumes FireEvent messages and dispatches a drone.
     */
    private void handleFireEvents() {
        try {
            while (true) {
                FireEvent event = bus.fireEvents.take();
                System.out.println("[Scheduler] Received fire event: " + event);

                synchronized (missionQueue) {
                    missionQueue.add(event);
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
        if (!droneBusy && !missionQueue.isEmpty()) {
            FireEvent nextMission = missionQueue.poll();
            droneBusy = true;
            bus.droneCommands.put(new DroneCommand(droneId, nextMission.zoneId(), nextMission.severity()));
            System.out.println("[Scheduler] Dispatched drone to Zone " + nextMission.zoneId());
        }
    }

    /**
     * Consumes DroneStatus messages from the DroneSubsystem.
     */
    private void handleDroneStatuses() {
        try {
            while (true) {
                DroneStatus status = bus.droneStatuses.take();
                System.out.println("[Scheduler] Received drone status: " + status);

                if (status.state() == DroneState.IDLE){
                    synchronized (this) { droneBusy = false;}
                    checkAndDispatch();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
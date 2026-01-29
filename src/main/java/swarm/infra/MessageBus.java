package swarm.infra;

import swarm.messages.FireEvent;
import swarm.messages.DroneCommand;
import swarm.messages.DroneStatus;
import swarm.messages.FireEvent;

/**
 * Shared infrastructure container. Wires queues together.
 * This is the only shared object all threads need references to.
 */
public class MessageBus {

    // FireIncident -> Scheduler
    public final swarm.infra.MonitorQueue<FireEvent> fireEvents;

    // Scheduler -> Drone
    public final MonitorQueue<DroneCommand> droneCommands;

    // Drone -> Scheduler
    public final MonitorQueue<DroneStatus> droneStatuses;

    public MessageBus(int capacity) {
        this.fireEvents = new MonitorQueue<>(capacity);
        this.droneCommands = new MonitorQueue<>(capacity);
        this.droneStatuses = new MonitorQueue<>(capacity);
    }
}
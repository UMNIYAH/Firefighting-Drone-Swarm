package swarm;

import org.junit.jupiter.api.Test;
import swarm.infra.MessageBus;
import swarm.messages.*;
import swarm.subsystems.Scheduler;

import static org.junit.jupiter.api.Assertions.*;

public class SchedulerTest {

    @Test
    public void testSchedulerPassThrough() throws Exception {
        MessageBus bus = new MessageBus(10);
        Scheduler scheduler = new Scheduler(bus);

        Thread schedulerThread = new Thread(scheduler);
        schedulerThread.start();

        // Send fire event
        bus.fireEvents.put(new FireEvent(
                1000L, 5, EventType.FIRE_DETECTED, Severity.HIGH
        ));

        // Receive drone command
        DroneCommand command = bus.droneCommands.take();
        assertEquals(1, command.droneId()); // Default drone ID
        assertEquals(5, command.zoneId());

        schedulerThread.interrupt();
        schedulerThread.join(1000);
    }

    /**
     * Scenario 2: Scheduler queues a fire if drone is busy and dispatches after drone idle.
     */
    @Test
    public void testQueueingWhenDroneBusy() throws Exception {
        MessageBus bus = new MessageBus(10);
        Scheduler scheduler = new Scheduler(bus);

        Thread schedulerThread = new Thread(scheduler);
        schedulerThread.start();

        // First fire → drone en route
        bus.fireEvents.put(new FireEvent(1000L, 1, EventType.FIRE_DETECTED, Severity.HIGH));
        DroneCommand command1 = bus.droneCommands.take();
        assertEquals(1, command1.zoneId());

        // Second fire → should queue
        bus.fireEvents.put(new FireEvent(2000L, 2, EventType.FIRE_DETECTED, Severity.MODERATE));
        assertEquals(0, bus.droneCommands.size(), "Second fire should wait in scheduler queue");

        // Simulate drone completing first fire
        bus.droneStatuses.put(new DroneStatus(1, DroneState.IDLE, 1,10)); //10 Liters

        // Next command should now be issued
        DroneCommand command2 = bus.droneCommands.take();
        assertEquals(2, command2.zoneId());

        schedulerThread.interrupt();
        schedulerThread.join(1000);
    }
}
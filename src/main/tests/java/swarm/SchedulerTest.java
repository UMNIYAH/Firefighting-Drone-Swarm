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
}
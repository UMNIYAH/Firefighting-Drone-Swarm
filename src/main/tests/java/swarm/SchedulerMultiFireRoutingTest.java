package swarm;

import org.junit.jupiter.api.Test;
import swarm.infra.MessageBus;
import swarm.messages.*;
import swarm.subsystems.Scheduler;

import static org.junit.jupiter.api.Assertions.*;

public class SchedulerMultiFireRoutingTest {

    @Test
    public void testMultiFireRouting() throws Exception {
        MessageBus bus = new MessageBus(20);
        Scheduler scheduler = new Scheduler(bus);

        Thread schedulerThread = new Thread(scheduler);
        schedulerThread.start();

        bus.droneStatuses.put(new DroneStatus(1, DroneState.IDLE, null, 30));

        bus.fireEvents.put(new FireEvent(1000L, 1, EventType.FIRE_DETECTED, Severity.LOW));
        DroneCommand cmd1 = bus.droneCommands.take();
        assertEquals(1, cmd1.zoneId());

        bus.droneStatuses.put(new DroneStatus(1, DroneState.IDLE, null, 20));

        bus.fireEvents.put(new FireEvent(2000L, 2, EventType.FIRE_DETECTED, Severity.LOW));
        DroneCommand cmd2 = bus.droneCommands.take();
        assertEquals(2, cmd2.zoneId());

        schedulerThread.interrupt();
    }
}

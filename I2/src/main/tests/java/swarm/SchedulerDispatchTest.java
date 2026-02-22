package swarm;

import org.junit.jupiter.api.Test;
import swarm.infra.MessageBus;
import swarm.infra.ZoneManager;
import swarm.messages.*;
import swarm.subsystems.DroneSubsystem;
import swarm.subsystems.Scheduler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import java.io.File;
import java.io.FileWriter;

import static org.junit.jupiter.api.Assertions.*;

public class SchedulerDispatchTest {

    @Test
    public void testSchedulerDispatchesDroneCorrectly() throws Exception {
        MessageBus bus = new MessageBus(10);

        Scheduler scheduler = new Scheduler(bus);
        Thread schedulerThread = new Thread(scheduler);
        schedulerThread.start();

        // Simulate drone is idle
        bus.droneStatuses.put(new DroneStatus(1, DroneState.IDLE, null, 30));

        // Send fire event
        bus.fireEvents.put(new FireEvent(
                System.currentTimeMillis(), 2, EventType.FIRE_DETECTED, Severity.MODERATE
        ));

        // Scheduler should dispatch immediately
        DroneCommand cmd = bus.droneCommands.take();

        assertEquals(1, cmd.droneId());
        assertEquals(2, cmd.zoneId());
        assertEquals(Severity.MODERATE, cmd.severity());

        schedulerThread.interrupt();
    }



}
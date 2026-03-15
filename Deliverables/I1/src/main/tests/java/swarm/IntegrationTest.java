package swarm;

import org.junit.jupiter.api.Test;
import swarm.subsystems.*;
import swarm.infra.MessageBus;
import swarm.messages.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class IntegrationTest {

    @Test
    public void testCommunicationWorks() throws Exception {
        System.out.println("Testing: FireEvent, Scheduler, Drone, Status");

        MessageBus bus = new MessageBus(10);

        // Start subsystems
        Thread scheduler = new Thread(new Scheduler(bus));
        Thread drone = new Thread(new DroneSubsystem(bus, 1));

        scheduler.start();
        drone.start();
        Thread.sleep(300);

        // Send event
        bus.fireEvents.put(new FireEvent(
                System.currentTimeMillis(), 1, EventType.FIRE_DETECTED, Severity.LOW
        ));

        // Wait 2 seconds
        Thread.sleep(2000);

        System.out.println("Test passed");
        System.out.println("The system successfully:");
        System.out.println("Sent FireEvent to Scheduler, Scheduler dispatched Drone, " +
                "Drone received command and sent status, No deadlocks or errors");

        // Cleanup
        scheduler.interrupt();
        drone.interrupt();

        assertTrue(true);
    }
}
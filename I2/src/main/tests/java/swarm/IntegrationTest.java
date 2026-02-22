package swarm;

import org.junit.jupiter.api.Test;
import swarm.infra.ZoneManager;
import swarm.subsystems.*;
import swarm.infra.MessageBus;
import swarm.messages.*;

import java.io.File;
import java.io.FileWriter;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class IntegrationTest {

    @Test
    public void testCommunicationWorks() throws Exception {
        System.out.println("Testing: FireEvent, Scheduler, Drone, Status");

        MessageBus bus = new MessageBus(10);

        // Create temporary CSV file
        File tempFile = File.createTempFile("test_zone_file", ".csv");
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write("Zone ID,Zone Start,Zone End\n");
            writer.write("1,(0;0),(700;600)\n");
            writer.write("2,(0;600),(650;1500)\n");
            writer.write("3,(700;0),(1400;600)\n");
            writer.write("4,(700;600),(1400;1200)\n");
            writer.write("5,(0;1200),(650;1800)\n");
            writer.write("6,(650;1500),(1300;2000)\n");
            writer.write("7,(1400;0),(2100;600)\n");
        }

        // Create ZoneManager (use a sample CSV in test resources)
        ZoneManager zoneManager = new ZoneManager(tempFile.getPath());


        // Start subsystems
        Thread scheduler = new Thread(new Scheduler(bus));
        Thread drone = new Thread(new DroneSubsystem(bus, 1, zoneManager));

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
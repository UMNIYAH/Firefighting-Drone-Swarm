package swarm;

import org.junit.jupiter.api.Test;
import swarm.infra.MessageBus;
import swarm.subsystems.FireIncidentSubsystem;

import java.io.FileWriter;
import java.io.File;
import static org.junit.jupiter.api.Assertions.*;

public class FireIncidentSubsystemTest {

    @Test
    public void testCSVParsing() throws Exception {
        // Create temporary CSV file
        File tempFile = File.createTempFile("test-events", ".csv");
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write("Time,Zone ID,Event type,Severity\n");
            writer.write("14:03:15,3,FIRE_DETECTED,High\n");
            writer.write("14:10:00,7,DRONE_REQUEST,Moderate\n");
        }

        MessageBus bus = new MessageBus(5);
        FireIncidentSubsystem subsystem = new FireIncidentSubsystem(bus, tempFile.getPath());

        // Start subsystem
        Thread thread = new Thread(subsystem);
        thread.start();

        // Produce 2 events
        int eventsReceived = 0;
        try {
            eventsReceived += (bus.fireEvents.take() != null) ? 1 : 0;
            eventsReceived += (bus.fireEvents.take() != null) ? 1 : 0;
        } finally {
            thread.interrupt();
            thread.join(1000);
            tempFile.delete();
        }

        assertEquals(2, eventsReceived, "Should parse 2 events from CSV");
    }
}
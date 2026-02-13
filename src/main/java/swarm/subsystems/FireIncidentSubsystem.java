package swarm.subsystems;

import swarm.infra.MessageBus;
import swarm.messages.DroneStatus;
import swarm.messages.EventType;
import swarm.messages.FireEvent;
import swarm.messages.Severity;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * FireIncidentSubsystems simulates fire event and monitors drone statuses
 *
 * Responsibilities
 * 1. Reads the fire events from a CSV file and sends them to the MessageBus
 * 2. Listens for drone status updates from the MessageBus
 *
 * Iteration 1:
 */
public class FireIncidentSubsystem implements Runnable{
    private final MessageBus bus;
    private final String inputFileName;

    public FireIncidentSubsystem(MessageBus bus, String inputFileName) {
        this.bus = bus;
        this.inputFileName = inputFileName;
    }

    @Override
    public void run() {
        try {
            readIncidents(inputFileName);
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }

        // Thread that listens for drone statuses from the MessageBus
        try {
            while (true) {
                DroneStatus status = bus.droneStatusToFire.take();
                System.out.println("[FireIncidentSubsystem] Drone " + status.state() + " at zone" + status.currentZoneId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Reads fire events from a csv file and passes them to MessageBus
    private void readIncidents(String inputFileName) throws IOException, InterruptedException {
        try (BufferedReader br = new BufferedReader(new FileReader(inputFileName))) {
            String line;

            while ((line = br.readLine()) != null) {
                // Skip empty lines and CSV Header
                if (line.trim().isEmpty() || line.toLowerCase().startsWith("time")) continue;

                // Separate The line into individual parameters
                String[] parameter = line.split(",");

                // Skip invalid lines
                if (parameter.length != 4) { continue; }

                try {

                    // Parses timestamp: Converts HH:mm:ss to epoch milliseconds
                    String timeStr = parameter[0].trim();
                    LocalTime localTime = LocalTime.parse(timeStr);
                    ZonedDateTime zonedDateTime = localTime.atDate(LocalDate.now()).atZone(ZoneId.systemDefault());
                    long timestamp = zonedDateTime.toInstant().toEpochMilli();

                    // Parses zoneID, eventType, severity
                    int zoneId = Integer.parseInt(parameter[1].trim());
                    EventType type = EventType.valueOf(parameter[2].trim());
                    Severity severity = Severity.valueOf(parameter[3].trim().toUpperCase());

                    // Trim whitespace, create FireIncident object and send to MessageBus
                    FireEvent incident = new FireEvent(timestamp, zoneId, type, severity);
                    bus.fireEvents.put(incident);

                } catch (Exception e) {
                    System.err.println("Error Parsing Line, skipping: " + line);
                }
            }
        }
    }
}

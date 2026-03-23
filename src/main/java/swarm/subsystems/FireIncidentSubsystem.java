package swarm.subsystems;

import swarm.infra.UDPHelper;
import swarm.main.SimulatorGUI;
import swarm.messages.DroneStatus;
import swarm.messages.EventType;
import swarm.messages.FaultType;
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
    private final UDPHelper udp;
    private final String inputFileName;

    public FireIncidentSubsystem(UDPHelper udp, String inputFileName) {
        this.udp = udp;
        this.inputFileName = inputFileName;
    }

    @Override
    public void run() {
        // Event reader
        new Thread(this::runEventReader, "FireEventReader").start();
    }

    // Reads file and pushes events to the Scheduler
    private void runEventReader(){
        try{
            readIncidents(inputFileName);
            System.out.println("[FireIncident] Completed reading events from file.");
        } catch (Exception e){
            System.err.println("[FireIncident] Reader encountered an error.");
            e.printStackTrace();
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

                // Skip invalid lines (accept 4 columns or 5 with fault)
                if (parameter.length < 4 || parameter.length > 5) { continue; }

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

                    // Parse fault type; default to NONE if absent
                    FaultType fault = (parameter.length >= 5)
                            ? FaultType.valueOf(parameter[4].trim().toUpperCase())
                            : FaultType.NONE;

                    // Serialization and UDP
                    String message = "FIRE:" + zoneId + ":" + severity.name() + ":" + type.name()
                            + ":" + fault.name();
                    udp.send(message, 5000);

                    Thread.sleep(1000);

                } catch (Exception e) {
                    System.err.println("Error Parsing Line, skipping: " + line);
                }
            }
        }
    }
    public static void main(String[] args){
        System.out.println("Starting Fire subystem");
        try{
            // connect to network
            swarm.infra.UDPHelper udp = new swarm.infra.UDPHelper();

            // Start subsystem
            FireIncidentSubsystem fire = new FireIncidentSubsystem(udp, "Sample_event_file.csv");
            fire.run();
        } catch (Exception e){
            System.err.println("Failed to start Fire subsystem");
            e.printStackTrace();
        }
    }
}
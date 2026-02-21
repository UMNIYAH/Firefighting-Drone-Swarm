package swarm;

import org.junit.jupiter.api.Test;
import swarm.infra.MessageBus;
import swarm.infra.ZoneManager;
import swarm.messages.*;
import swarm.subsystems.DroneSubsystem;

import java.io.File;
import java.io.FileWriter;

import static org.junit.jupiter.api.Assertions.*;

public class DroneSubsystemTest {

    @Test
    public void testDroneProcessesCommand() throws Exception {
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

        // Create ZoneManager (use the temp csv)
        ZoneManager zoneManager = new ZoneManager(tempFile.getPath());

        // Create DroneSubsystem with proper constructor
        DroneSubsystem drone = new DroneSubsystem(bus, 1, zoneManager);

        // Fast DroneSubsystem override to skip sleep
        DroneSubsystem fastDrone = new DroneSubsystem(bus, 1, zoneManager) {
            @Override
            public void run() {
                new Thread(() -> {
                    try {
                        while (true) {
                            // Report IDLE
                            bus.droneStatuses.put(new DroneStatus(1, DroneState.IDLE, null, 30));
                            // Wait for command
                            DroneCommand cmd = bus.droneCommands.take();

                            // Report EN_ROUTE → DROPPING_AGENT → RETURNING → REFILLING immediately
                            bus.droneStatuses.put(new DroneStatus(1, DroneState.EN_ROUTE, cmd.zoneId(), 30));
                            bus.droneStatuses.put(new DroneStatus(1, DroneState.DROPPING_AGENT, cmd.zoneId(), 30));
                            bus.droneStatuses.put(new DroneStatus(1, DroneState.RETURNING, null, 0));
                            bus.droneStatuses.put(new DroneStatus(1, DroneState.REFILLING, null, 30));
                        }
                    } catch (InterruptedException ignored) {}
                }).start();
            }
        };

        Thread droneThread = new Thread((fastDrone)); //Fast
        //Thread droneThread = new Thread((drone)); //Regular Speed
        droneThread.start();

        // Send command to drone
        bus.droneCommands.put(new DroneCommand(1, 3, Severity.MODERATE));

        // Receive drone statuses
        DroneStatus s1 = bus.droneStatuses.take();
        assertEquals(DroneState.IDLE, s1.state());

        DroneStatus s2 = bus.droneStatuses.take();
        assertEquals(DroneState.EN_ROUTE, s2.state());

        DroneStatus s3 = bus.droneStatuses.take();
        assertEquals(DroneState.DROPPING_AGENT, s3.state());

        DroneStatus s4 = bus.droneStatuses.take();
        assertEquals(DroneState.RETURNING, s4.state());

        DroneStatus s5 = bus.droneStatuses.take();
        assertEquals(DroneState.REFILLING, s5.state());

        droneThread.interrupt();
        droneThread.join(1000);
    }
}
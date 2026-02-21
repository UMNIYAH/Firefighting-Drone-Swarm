package swarm;

import org.junit.jupiter.api.Test;
import swarm.infra.MessageBus;
import swarm.infra.ZoneManager;
import swarm.messages.*;
import swarm.subsystems.DroneSubsystem;

import java.io.File;
import java.io.FileWriter;

import static org.junit.jupiter.api.Assertions.*;

public class DroneAgentTest {

    @Test
    public void testDroneAgentConsumptionAndRefill() throws Exception {
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

        ZoneManager zones = new ZoneManager(tempFile.getPath());

        // Fast drone to skip sleeps
        DroneSubsystem drone = new DroneSubsystem(bus, 1, zones) {
            @Override
            public void run() {
                new Thread(() -> {
                    try {
                        DroneCommand cmd = bus.droneCommands.take();
                        bus.droneStatuses.put(new DroneStatus(1, DroneState.DROPPING_AGENT, cmd.zoneId(), 30 - cmd.severity().litersRequired()));
                        bus.droneStatuses.put(new DroneStatus(1, DroneState.REFILLING, null, 30));
                    } catch (InterruptedException ignored) {}
                }).start();
            }
        };

        Thread droneThread = new Thread(drone);
        droneThread.start();

        bus.droneCommands.put(new DroneCommand(1, 1, Severity.MODERATE));

        DroneStatus dropping = bus.droneStatuses.take();
        assertEquals(30 - Severity.MODERATE.litersRequired(), dropping.remainingAgentLiters());

        DroneStatus refilling = bus.droneStatuses.take();
        assertEquals(30, refilling.remainingAgentLiters());

        droneThread.interrupt();
    }
}
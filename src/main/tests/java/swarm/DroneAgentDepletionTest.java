package swarm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import swarm.infra.DroneConfig;
import swarm.infra.MessageBus;
import swarm.infra.ZoneManager;
import swarm.messages.*;
import swarm.subsystems.DroneSubsystem;

import java.io.File;
import java.io.FileWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DroneAgentDepletionTest {

    @BeforeEach
    public void fastMode() {
        DroneConfig.enableTestMode();
    }

    @Test
    public void testAgentDepletionAndRefill() throws Exception {
        MessageBus bus = new MessageBus(20);

        File tempFile = File.createTempFile("zones", ".csv");
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write("Zone ID,Zone Start,Zone End\n");
            writer.write("1,(0;0),(700;600)\n");
        }

        ZoneManager zones = new ZoneManager(tempFile.getPath());

        DroneSubsystem drone = new DroneSubsystem(bus, 1, zones);
        Thread droneThread = new Thread(drone);
        droneThread.start();

        bus.droneCommands.put(new DroneCommand(1, 1, Severity.MODERATE));

        // 1. IDLE
        DroneStatus s1 = bus.droneStatuses.take();
        assertEquals(DroneState.IDLE, s1.state());
        assertEquals(30, s1.remainingAgentLiters());

        // 2. EN_ROUTE
        DroneStatus s2 = bus.droneStatuses.take();
        assertEquals(DroneState.EN_ROUTE, s2.state());
        assertEquals(30, s2.remainingAgentLiters());

        // 3. DROPPING_AGENT
        DroneStatus s3 = bus.droneStatuses.take();
        assertEquals(DroneState.DROPPING_AGENT, s3.state());
        assertEquals(30, s3.remainingAgentLiters());

        // 4. RETURNING
        DroneStatus s4 = bus.droneStatuses.take();
        assertEquals(DroneState.RETURNING, s4.state());
        assertEquals(10, s4.remainingAgentLiters());

        // 5. REFILLING
        DroneStatus s5 = bus.droneStatuses.take();
        assertEquals(DroneState.REFILLING, s5.state());
        assertEquals(30, s5.remainingAgentLiters());

        // 6. IDLE (end of mission loop)
        DroneStatus s6 = bus.droneStatuses.take();
        assertEquals(DroneState.IDLE, s6.state());
        assertEquals(30, s6.remainingAgentLiters());

        droneThread.interrupt();
    }
}

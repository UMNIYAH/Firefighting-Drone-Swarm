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

import static swarm.messages.DroneState.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class DroneStateMachineTest {

    @BeforeEach
    public void fastMode() {
        DroneConfig.enableTestMode();
    }

    @Test
    public void testDroneFullStateMachine() throws Exception {
        MessageBus bus = new MessageBus(20);

        File tempFile = File.createTempFile("zones", ".csv");
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write("Zone ID,Zone Start,Zone End\n");
            writer.write("1,(0;0),(700;600)\n");
        }

        ZoneManager zones = new ZoneManager(tempFile.getPath());

        DroneSubsystem drone = new DroneSubsystem(bus, 1, zones);
        Thread droneThread = new Thread(drone, "Drone-1-StateMachine");
        droneThread.start();

        bus.droneCommands.put(new DroneCommand(1, 1, Severity.LOW));

        assertEquals(IDLE,          bus.droneStatuses.take().state());
        assertEquals(EN_ROUTE,      bus.droneStatuses.take().state());
        assertEquals(DROPPING_AGENT,bus.droneStatuses.take().state());
        assertEquals(RETURNING,     bus.droneStatuses.take().state());
        assertEquals(REFILLING,     bus.droneStatuses.take().state());
        assertEquals(IDLE,          bus.droneStatuses.take().state());

        droneThread.interrupt();
    }
}

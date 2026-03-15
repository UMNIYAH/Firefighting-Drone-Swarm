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

import static org.junit.jupiter.api.Assertions.assertTrue;

public class DroneTravelTimeTest {

    @BeforeEach
    public void fastMode() {
        DroneConfig.enableTestMode();
    }

    @Test
    public void testTravelTimeMatchesDistanceFast() throws Exception {
        MessageBus bus = new MessageBus(20);

        File tempFile = File.createTempFile("zones", ".csv");
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write("Zone ID,Zone Start,Zone End\n");
            writer.write("1,(0;0),(700;600)\n");
        }

        ZoneManager zones = new ZoneManager(tempFile.getPath());

        DroneSubsystem drone = new DroneSubsystem(bus, 1, zones);
        Thread droneThread = new Thread(drone, "Drone-1-Travel");
        droneThread.start();

        bus.droneCommands.put(new DroneCommand(1, 1, Severity.LOW));

        bus.droneStatuses.take(); // IDLE

        DroneStatus enRoute = bus.droneStatuses.take(); // EN_ROUTE
        long start = System.currentTimeMillis();

        bus.droneStatuses.take(); // DROPPING_AGENT (arrival)
        long actualTravel = System.currentTimeMillis() - start;

        long expected = DroneConfig.travelTimeMillis(
                DroneConfig.BASE_POSITION.distanceTo(zones.getZoneCenter(1))
        );

        // In test mode, expected is 5 ms;
        assertTrue(Math.abs(actualTravel - expected) < 50,
                "Expected ~" + expected + "ms but got " + actualTravel + "ms");

        droneThread.interrupt();
    }
}

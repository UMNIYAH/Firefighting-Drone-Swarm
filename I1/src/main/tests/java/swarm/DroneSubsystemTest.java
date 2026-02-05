package swarm;

import org.junit.Test;
import swarm.infra.MessageBus;
import swarm.messages.*;
import swarm.subsystems.DroneSubsystem;

import static org.junit.jupiter.api.Assertions.*;

public class DroneSubsystemTest {

    @Test
    public void testDroneProcessesCommand() throws Exception {
        MessageBus bus = new MessageBus(10);
        DroneSubsystem drone = new DroneSubsystem(bus, 1);

        Thread droneThread = new Thread(drone);
        droneThread.start();

        // Send command to drone
        bus.droneCommands.put(new DroneCommand(1, 3, Severity.MODERATE));

        // Receive EN_ROUTE then ARRIVED status
        DroneStatus status1 = bus.droneStatuses.take();
        assertEquals("EN_ROUTE", status1.state());

        DroneStatus status2 = bus.droneStatuses.take();
        assertEquals("ARRIVED", status2.state());

        droneThread.interrupt();
        droneThread.join(1000);
    }
}
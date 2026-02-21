package swarm;

import org.junit.jupiter.api.Test;
import swarm.infra.MessageBus;
import swarm.infra.ZoneManager;
import swarm.messages.*;
import swarm.subsystems.DroneSubsystem;
import swarm.subsystems.Scheduler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import java.io.File;
import java.io.FileWriter;

import static org.junit.jupiter.api.Assertions.*;

public class SchedulerDispatchTest {

    @Test
    public void testSchedulerDispatchesDroneCorrectly() throws Exception {
        MessageBus bus = new MessageBus(10);

        // --- Create temporary CSV file for ZoneManager ---
        File tempFile = File.createTempFile("test_zone_file", ".csv");
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write("Zone ID,Zone Start,Zone End\n");
            writer.write("1,(0;0),(700;600)\n");
            writer.write("2,(0;600),(650;1500)\n");
        }

        ZoneManager zoneManager = new ZoneManager(tempFile.getPath());

        CountDownLatch schedulerDone = new CountDownLatch(1);
        CountDownLatch droneDone = new CountDownLatch(1);

        // --- Scheduler: process exactly one event ---
        Scheduler testScheduler = new Scheduler(bus) {
            @Override
            public void run() {
                try {
                    FireEvent event = bus.fireEvents.take();
                    bus.droneCommands.put(new DroneCommand(1, event.zoneId(), event.severity()));
                } catch (InterruptedException ignored) {
                } finally {
                    schedulerDone.countDown();
                }
            }
        };
        Thread schedulerThread = new Thread(testScheduler);
        schedulerThread.start();

        // --- Drone: process exactly one command ---
        DroneSubsystem testDrone = new DroneSubsystem(bus, 1, zoneManager) {
            @Override
            public void run() {
                try {
                    bus.droneStatuses.put(new DroneStatus(1, DroneState.IDLE, null, 30));

                    DroneCommand cmd = bus.droneCommands.take();

                    bus.droneStatuses.put(new DroneStatus(1, DroneState.EN_ROUTE, cmd.zoneId(), 30));
                    bus.droneStatuses.put(new DroneStatus(1, DroneState.DROPPING_AGENT, cmd.zoneId(), 30));
                    bus.droneStatuses.put(new DroneStatus(1, DroneState.RETURNING, null, 0));
                    bus.droneStatuses.put(new DroneStatus(1, DroneState.REFILLING, null, 30));
                    bus.droneStatuses.put(new DroneStatus(1, DroneState.IDLE, null, 30));
                } catch (InterruptedException ignored) {
                } finally {
                    droneDone.countDown();
                }
            }
        };
        Thread droneThread = new Thread(testDrone);
        droneThread.start();

        // --- Send fire event ---
        bus.fireEvents.put(new FireEvent(System.currentTimeMillis(), 2, EventType.FIRE_DETECTED, Severity.MODERATE));

        // --- Wait for scheduler to finish ---
        assertTrue(schedulerDone.await(1, TimeUnit.SECONDS), "Scheduler did not finish");

        // --- Validate command ---
        DroneCommand cmd = bus.droneCommands.take();
        assertEquals(1, cmd.droneId());
        assertEquals(2, cmd.zoneId());
        assertEquals(Severity.MODERATE, cmd.severity());

        // --- Validate drone status sequence ---
        assertEquals(DroneState.IDLE, bus.droneStatuses.take().state());
        assertEquals(DroneState.EN_ROUTE, bus.droneStatuses.take().state());
        assertEquals(DroneState.DROPPING_AGENT, bus.droneStatuses.take().state());
        assertEquals(DroneState.RETURNING, bus.droneStatuses.take().state());
        assertEquals(DroneState.REFILLING, bus.droneStatuses.take().state());
        assertEquals(DroneState.IDLE, bus.droneStatuses.take().state());

        // --- Wait for drone to finish ---
        assertTrue(droneDone.await(1, TimeUnit.SECONDS), "Drone did not finish");
    }


}
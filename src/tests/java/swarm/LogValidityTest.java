package swarm;

import org.junit.jupiter.api.*;
import swarm.infra.DroneConfig;
import swarm.infra.ZoneManager;
import swarm.main.MetricsCollector;
import swarm.messages.FaultType;
import swarm.messages.Severity;
import swarm.subsystems.DroneSubsystem;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class LogValidityTest {

    private static final int NUM_DRONES = 2;
    private static final int SCHED_PORT = 5000;

    private DroneSubsystem[] drones = new DroneSubsystem[NUM_DRONES];
    private Thread[] threads = new Thread[NUM_DRONES];
    private DatagramSocket schedSpySocket;
    private File zoneFile;

    private MetricsCollector metricsCollector;

    @BeforeAll
    void setup() throws Exception {
        // Use test mode
        DroneConfig.enableTestMode();

        // Create temporary zone CSV
        zoneFile = File.createTempFile("drone_fault_zones", ".csv");
        try (FileWriter w = new FileWriter(zoneFile)) {
            w.write("Zone ID,Zone Start,Zone End\n");
            w.write("1,(0,0),(700,500)\n");
            w.write("2,(700,0),(1400,500)\n");
            w.write("3,(1400,0),(2100,500)\n");
            w.write("4,(0,500),(700,1000)\n");
            w.write("5,(700,500),(1400,1000)\n");
            w.write("6,(1400,500),(2100,1000)\n");
            w.write("7,(0,1000),(700,1500)\n");
            w.write("8,(700,1000),(1400,1500)\n");
            w.write("9,(1400,1000),(2100,1500)\n");
        }

        ZoneManager zm = new ZoneManager(zoneFile.getPath());

        // Socket to spy on STATUS messages sent by drones
        schedSpySocket = new DatagramSocket(SCHED_PORT);
        schedSpySocket.setSoTimeout(500);

        // Start drones on UDP ports
        for (int i = 0; i < NUM_DRONES; i++) {
            int droneId = i + 1;
            drones[i] = new DroneSubsystem(new swarm.infra.UDPHelper(6000 + droneId), droneId, 6000 + droneId, zm);
            threads[i] = new Thread(drones[i], "Drone-" + droneId + "-Mission");
            threads[i].setDaemon(true);
            threads[i].start();
        }

        Thread.sleep(500); // Give drones time to start

        // Initialize MetricsCollector
        metricsCollector = new MetricsCollector();
        MetricsCollector.instance = metricsCollector;
    }

    @AfterAll
    void cleanup() throws Exception {
        for (DroneSubsystem drone : drones) stopDrone(drone);
        for (Thread t : threads) if (t != null) t.join(1000);

        if (schedSpySocket != null) schedSpySocket.close();
        if (zoneFile != null && zoneFile.exists()) zoneFile.delete();

        DroneConfig.disableTestMode();
    }

    @BeforeEach
    void resetMetrics() {
        metricsCollector = new MetricsCollector();
        MetricsCollector.instance = metricsCollector;
    }

    private void stopDrone(DroneSubsystem drone) throws Exception {
        if (drone == null) return;
        Field f = drone.getClass().getDeclaredField("hardFaulted");
        f.setAccessible(true);
        f.setBoolean(drone, true);
    }

    private void sendCmd(int dronePort, int zoneId, Severity severity, FaultType fault) throws Exception {
        String msg = "CMD:" + zoneId + ":" + severity.name() + ":" + fault.name();
        byte[] data = msg.getBytes();
        try (var socket = new java.net.DatagramSocket()) {
            socket.send(new DatagramPacket(data, data.length, InetAddress.getLocalHost(), dronePort));
        }
    }

    private void feedStatusesToCollector(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                byte[] buf = new byte[1024];
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                schedSpySocket.receive(pkt);
                String msg = new String(pkt.getData(), 0, pkt.getLength()).trim();
                processStatusMessage(msg);
            } catch (Exception ignored) {}
        }
    }

    private void waitForAllMissions(int[] expectedMissionsPerDrone, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            feedStatusesToCollector(200); // short bursts
            boolean allDone = true;
            for (int i = 0; i < expectedMissionsPerDrone.length; i++) {
                if (metricsCollector.droneMissionsCompleted(i + 1) < expectedMissionsPerDrone[i]) {
                    allDone = false;
                    break;
                }
            }
            if (allDone) return;
            Thread.sleep(50); // give drones time to progress
        }
    }

    // Maps drone STATUS messages into MetricsCollector
    private void processStatusMessage(String msg) {
        System.out.println("[STATUS RECEIVED] " + msg); // <-- log every STATUS message

        String[] parts = msg.split(":");
        if (parts.length < 7 || !parts[0].equals("STATUS")) return;

        int droneId = Integer.parseInt(parts[1]);
        String state = parts[2];
        int zoneId = Integer.parseInt(parts[3]);
        FaultType fault = FaultType.valueOf(parts[6]);

        switch (state) {
            case "EN_ROUTE", "RETURNING" -> metricsCollector.recordDroneStateChange(droneId, "IDLE", state);
            case "ARRIVED" -> metricsCollector.recordDroneArrived(droneId);
            case "DROPPING_AGENT" -> metricsCollector.recordFireExtinguished(droneId);
            case "IDLE" -> metricsCollector.recordMissionComplete(droneId);
        }
    }

    @Test
    void testMetricsCollectorWithUdpDrones() throws Exception {
        // Fire detected → create mission in metrics
        int mission1 = metricsCollector.recordFireDetected(1, "MODERATE");
        int mission2 = metricsCollector.recordFireDetected(3, "HIGH");
        int mission3 = metricsCollector.recordFireDetected(2, "HIGH");

        // Assign drones (simulate Scheduler dispatch)
        metricsCollector.recordDroneDispatched(mission1, 1);
        metricsCollector.recordDroneDispatched(mission2, 1);
        metricsCollector.recordDroneDispatched(mission3, 2);

        // Send CMDs to drones
        sendCmd(6001, 1, Severity.MODERATE, FaultType.NONE);
        sendCmd(6001, 3, Severity.HIGH, FaultType.NONE);
        sendCmd(6002, 2, Severity.HIGH, FaultType.NONE);

        // Collect STATUS messages
        waitForAllMissions(new int[]{1, 1}, 15000); // wait up to 15s


        // Assertions
        assertTrue(metricsCollector.avgDroneFlightTimeMs() > 0,"Flight time should be > 0" );
        assertTrue(metricsCollector.avgExtinguishTimeMs() > 0, "Extinguish time should be > 0");
        assertEquals(2, metricsCollector.droneMissionsCompleted(1), "Drone 1 should complete 2 mission");
        assertEquals(1, metricsCollector.droneMissionsCompleted(2), "Drone 2 failed hard, so 1 missions");

        System.out.println(metricsCollector.buildSummary());
    }

    @Test
    void testDroneStuckDuringMission() throws Exception {
        // Fire detected → create mission
        int mission1 = metricsCollector.recordFireDetected(1, "MODERATE");
        int mission2 = metricsCollector.recordFireDetected(2, "HIGH");

        // Assign drones
        metricsCollector.recordDroneDispatched(mission1, 1);
        metricsCollector.recordDroneDispatched(mission2, 2);

        // Send CMDs
        sendCmd(6001, 1, Severity.MODERATE, FaultType.NONE);      // Drone 1 healthy
        sendCmd(6002, 2, Severity.HIGH, FaultType.DRONE_STUCK);   // Drone 2 stuck

        // Collect STATUS messages
        waitForAllMissions(new int[]{1, 0}, 10000);

        // Assertions
        assertTrue(metricsCollector.avgDroneFlightTimeMs() > 0,"Flight time should be > 0" );
        assertTrue(metricsCollector.avgExtinguishTimeMs() > 0, "Extinguish time should be > 0 for Drone 1");
        assertEquals(1, metricsCollector.droneMissionsCompleted(1), "Drone 1 should complete its mission");
        assertEquals(0, metricsCollector.droneMissionsCompleted(2), "Drone 2 stuck → no completed mission");

        System.out.println(metricsCollector.buildSummary());
    }

    @Test
    void testDroneNozzleJammed() throws Exception {
        // Fire detected → create mission
        int mission1 = metricsCollector.recordFireDetected(1, "HIGH");

        // Assign drone
        metricsCollector.recordDroneDispatched(mission1, 2);

        // Send CMD that causes a nozzle jam
        sendCmd(6002, 1, Severity.HIGH, FaultType.NOZZLE_JAMMED);

        // Collect STATUS messages
        waitForAllMissions(new int[]{0, 0}, 10000);

        // Assertions
        assertTrue(metricsCollector.avgDroneFlightTimeMs() > 0,"Flight time should be > 0" );
        assertTrue(metricsCollector.avgExtinguishTimeMs() == 0, "Extinguish time should be = 0 for Drone 2");
        assertEquals(0, metricsCollector.droneMissionsCompleted(2), "Drone 2 jammed → no completed mission");

        System.out.println(metricsCollector.buildSummary());

    }

}
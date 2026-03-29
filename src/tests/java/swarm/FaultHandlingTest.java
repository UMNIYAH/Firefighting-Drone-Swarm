package swarm;

import org.junit.jupiter.api.*;
import swarm.infra.DroneConfig;
import swarm.infra.UDPHelper;
import swarm.infra.ZoneManager;
import swarm.messages.Severity;
import swarm.subsystems.DroneSubsystem;

import java.io.File;
import java.io.FileWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 tests for drone fault handling via real UDP sockets.
 *
 * Faults tested:
 *  - DRONE_STUCK (soft fault, recovers)
 *  - PACKET_LOSS (soft fault, recovers)
 *  - NOZZLE_JAMMED (hard fault, drone permanently offline)
 */

public class FaultHandlingTest {

    private static final int DRONE_ID   = 1;
    private static final int DRONE_PORT = 6001; // 6000 + droneId
    private static final int SCHED_PORT = 5000;

    private UDPHelper droneUdp;
    private DatagramSocket schedSpySocket;
    private Thread droneThread;
    private File zoneFile;
    private DroneSubsystem droneSubsystem;

    private void flushSocket() {
        try {
            schedSpySocket.setSoTimeout(50);
            while (true) {
                byte[] buf = new byte[1024];
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                schedSpySocket.receive(pkt);
            }
        } catch (Exception ignored) {
        } finally {
            try { schedSpySocket.setSoTimeout(5000); } catch (Exception ignored) {}
        }
    }
    
    @BeforeEach
    void setupZones() throws Exception {
        DroneConfig.enableTestMode();

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
        droneUdp = new UDPHelper(DRONE_PORT);
        schedSpySocket = new DatagramSocket(SCHED_PORT);
        schedSpySocket.setSoTimeout(500);

        flushSocket();

        droneSubsystem = new DroneSubsystem(droneUdp, DRONE_ID, DRONE_PORT, zm);
        droneThread = new Thread(droneSubsystem, "Drone-Fault-Test");
        droneThread.setDaemon(true);
        droneThread.start();

        // Give the drone a moment to initialize
        Thread.sleep(500);
    }

    @AfterEach
    void cleanup() throws Exception {
        DroneConfig.disableTestMode();

        if (droneSubsystem != null) {
            java.lang.reflect.Field f = droneSubsystem.getClass().getDeclaredField("hardFaulted");
            f.setAccessible(true);
            f.setBoolean(droneSubsystem, true); // stops mission loop
        }

        if (droneThread != null) {
            droneThread.interrupt();
            droneThread.join(1000); // give more time
        }

        if (droneUdp != null) droneUdp.close();
        if (schedSpySocket != null) schedSpySocket.close();
        if (zoneFile != null && zoneFile.exists()) zoneFile.delete();

        Thread.sleep(100);
    }

    // ---------------------------
    // Helpers
    // ---------------------------

    private Thread getMissionThread() {
        long deadline = System.currentTimeMillis() + 2000;

        while (System.currentTimeMillis() < deadline) {
            Thread t = Thread.getAllStackTraces().keySet().stream()
                    .filter(th -> th.getName().equals("Drone-" + DRONE_ID + "-Processor"))
                    .findFirst()
                    .orElse(null);

            if (t != null) return t;

            try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        }

        return null;
    }


    private void sendFaultCmd(int zoneId, Severity severity, String faultType) throws Exception {
        String msg = "CMD:" + zoneId + ":" + severity.name() + ":" + faultType;
        byte[] data = msg.getBytes();
        schedSpySocket.send(new DatagramPacket(data, data.length, InetAddress.getLocalHost(), DRONE_PORT));
    }

    private String receiveStatus() throws Exception {
        byte[] buf = new byte[1024];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        schedSpySocket.receive(pkt);
        return new String(pkt.getData(), 0, pkt.getLength()).trim();
    }

    private boolean waitForStatusContaining(String state, String fault, long timeoutMs) throws Exception {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                String status = receiveStatus();
                if (status.contains(state)) {
                    if (fault == null || status.contains(fault)) return true;
                }
            } catch (java.net.SocketTimeoutException e) {
                // Ignore dropped packets
            }
        }
        return false;
    }

    // ---------------------------
    // Tests
    // ---------------------------

    @Test
    void testDroneStuckFault() throws Exception {
        sendFaultCmd(1, Severity.MODERATE, "DRONE_STUCK");

        // Drone should enter FAULT state reporting DRONE_STUCK
        assertTrue(waitForStatusContaining("FAULT", "DRONE_STUCK", 5000),
                "Drone should report FAULT state on DRONE_STUCK");

        // Drone should eventually recover to IDLE
        assertTrue(waitForStatusContaining("IDLE", null, 5000),
                "Drone should recover from DRONE_STUCK and return to IDLE");
    }


    @Test
    void testPacketLossDoesNotStopDrone() throws Exception {
        sendFaultCmd(1, Severity.LOW, "NONE");


        // This simulates the packet loss
        int drops = 3;
        for (int i = 0; i < drops; i++) {
            try { receiveStatus(); } catch (SocketTimeoutException ignored) {}
        }

        Thread mission = getMissionThread();
        assertNotNull(mission, "Mission thread should exist");
        assertTrue(mission.isAlive(), "Mission loop should still be running");

        String status = null;
        long deadline = System.currentTimeMillis() + 3000;

        while (System.currentTimeMillis() < deadline) {
            try {
                status = receiveStatus();
                break;
            } catch (SocketTimeoutException ignored) {}
        }

        assertNotNull(status, "Drone should continue sending STATUS after packet loss");
    }

    // Tests how system reacts to jammed nozzle
    @Test
    void testNozzleJammedHardFault() throws Exception {
        sendFaultCmd(5, Severity.HIGH, "NOZZLE_JAMMED");

        // Drone should enter FAULT state reporting NOZZLE_JAMMED
        assertTrue(waitForStatusContaining("FAULT", "NOZZLE_JAMMED", 5000),
                "Drone should report FAULT state on NOZZLE_JAMMED");

        // Drone should never recover (hard fault)
        boolean recovered = waitForStatusContaining("IDLE", null, 3000);
        assertFalse(recovered, "Drone should remain offline after NOZZLE_JAMMED hard fault");
    }

    // Tests if the drone can recover from receiving multiple Drone_Stuck faults
    @Test
    void testMultipleDroneStuckFaults() throws Exception {
        for (int zone = 1; zone <= 3; zone++) {
            sendFaultCmd(zone, Severity.LOW, "DRONE_STUCK");
            assertTrue(waitForStatusContaining("FAULT", "DRONE_STUCK", 5000),
                    "Drone should report FAULT on DRONE_STUCK in zone " + zone);
            assertTrue(waitForStatusContaining("IDLE", null, 5000),
                    "Drone should recover from DRONE_STUCK and return to IDLE after zone " + zone);
        }
    }

    // Tests if the drone handles a soft then a hard fault
    @Test
    void testDroneStuckThenNozzleJammed() throws Exception {
        // First, soft fault
        sendFaultCmd(1, Severity.LOW, "DRONE_STUCK");
        assertTrue(waitForStatusContaining("FAULT", "DRONE_STUCK", 5000),
                "Drone should report FAULT on DRONE_STUCK");
        assertTrue(waitForStatusContaining("IDLE", null, 5000),
                "Drone should recover from DRONE_STUCK");

        // Then, hard fault
        sendFaultCmd(2, Severity.HIGH, "NOZZLE_JAMMED");
        assertTrue(waitForStatusContaining("FAULT", "NOZZLE_JAMMED", 5000),
                "Drone should report FAULT on NOZZLE_JAMMED");
        boolean recovered = waitForStatusContaining("IDLE", null, 3000);
        assertFalse(recovered, "Drone should remain offline after NOZZLE_JAMMED");
    }

    // Tests how system responds to invalid command
    @Test
    void testInvalidZoneCmdIgnored() throws Exception {
        sendFaultCmd(999, Severity.LOW, "DRONE_STUCK");

        // Wait to see if any STATUS gets reported
        boolean statusReceived = waitForStatusContaining("FAULT", "DRONE_STUCK", 3000);
        assertFalse(statusReceived, "Drone should ignore commands for invalid zones");
    }
}
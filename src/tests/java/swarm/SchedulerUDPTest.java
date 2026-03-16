package swarm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import swarm.infra.UDPHelper;
import swarm.infra.ZoneManager;
import swarm.messages.DroneState;
import swarm.messages.Severity;
import swarm.subsystems.Scheduler;

import java.io.File;
import java.io.FileWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests Scheduler message handling over real UDP sockets on localhost.
 *
 * The test acts as both the FireIncidentSubsystem (sending FIRE packets)
 * and as a drone spy (intercepting CMD packets on the drone port).
 *
 * Scheduler constructor: Scheduler(udp, numDrones, zoneManager)
 * STATUS format:         STATUS:<id>:<state>:<zone>:<posX>:<posY>
 *
 * Ports:
 *   Scheduler listens : 15000  (test-only — no conflict with live sim)
 *   Drone 1 spy       : 6001   (real drone port — stop sim before running)
 */
public class SchedulerUDPTest {

    private static final int SCHED_PORT      = 15000;
    private static final int DRONE1_SPY_PORT = 6001;

    private UDPHelper         schedulerUdp;
    private DatagramSocket    droneSpySocket;
    private DatagramSocket    fireSocket;
    private Thread            schedulerThread;
    private File              zoneFile;

    @BeforeEach
    public void setUp() throws Exception {
        zoneFile = File.createTempFile("sched_zones", ".csv");
        try (FileWriter w = new FileWriter(zoneFile)) {
            w.write("Zone ID,Zone Start,Zone End\n");
            w.write("1,(0, 0),(700, 600)\n");
            w.write("2,(700, 0),(1400, 600)\n");
            w.write("3,(0, 600),(700, 1200)\n");
            w.write("5,(700, 600),(1400, 1200)\n");
        }

        droneSpySocket = new DatagramSocket(DRONE1_SPY_PORT);
        droneSpySocket.setSoTimeout(3000);

        fireSocket = new DatagramSocket();
        fireSocket.setSoTimeout(3000);

        ZoneManager zm = new ZoneManager(zoneFile.getPath());
        schedulerUdp   = new UDPHelper(SCHED_PORT);
        schedulerThread = new Thread(new Scheduler(schedulerUdp, 1, zm), "Scheduler-Test");
        schedulerThread.setDaemon(true);
        schedulerThread.start();
        Thread.sleep(100);
    }

    @AfterEach
    public void tearDown() throws Exception {
        schedulerThread.interrupt();
        schedulerUdp.close();
        if (!droneSpySocket.isClosed()) droneSpySocket.close();
        fireSocket.close();
        zoneFile.delete();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void sendToScheduler(String msg) throws Exception {
        byte[] data = msg.getBytes();
        fireSocket.send(new DatagramPacket(
                data, data.length, InetAddress.getLocalHost(), SCHED_PORT));
    }

    /** Send a 6-part STATUS packet as a drone would. */
    private void sendStatus(int droneId, DroneState state, int zoneId,
                            double x, double y) throws Exception {
        sendToScheduler("STATUS:" + droneId + ":" + state.name()
                + ":" + zoneId + ":" + x + ":" + y);
    }

    private String receiveCmdOnSpy() throws Exception {
        byte[] buf = new byte[1024];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        droneSpySocket.receive(pkt);
        return new String(pkt.getData(), 0, pkt.getLength()).trim();
    }

    // -----------------------------------------------------------------------
    // Fire → CMD dispatch
    // -----------------------------------------------------------------------

    @Test
    public void testFireEventProducesCmdPacket() throws Exception {
        sendToScheduler("FIRE:1:HIGH:FIRE_DETECTED");
        String cmd = receiveCmdOnSpy();
        assertTrue(cmd.startsWith("CMD:"), "Expected CMD, got: " + cmd);
    }

    @Test
    public void testCmdContainsCorrectZoneId() throws Exception {
        sendToScheduler("FIRE:1:LOW:FIRE_DETECTED");
        assertEquals("1", receiveCmdOnSpy().split(":")[1]);
    }

    @Test
    public void testCmdContainsCorrectSeverity() throws Exception {
        sendToScheduler("FIRE:1:MODERATE:FIRE_DETECTED");
        assertEquals("MODERATE", receiveCmdOnSpy().split(":")[2]);
    }

    @Test
    public void testAllSeveritiesDispatchedCorrectly() throws Exception {
        for (Severity s : Severity.values()) {
            // Reset drone to IDLE before each fire
            sendStatus(1, DroneState.IDLE, 0, 0.0, 0.0);
            Thread.sleep(60);

            sendToScheduler("FIRE:1:" + s.name() + ":FIRE_DETECTED");
            String cmd = receiveCmdOnSpy();
            assertTrue(cmd.contains(s.name()),
                    "CMD should contain " + s.name() + ", got: " + cmd);
        }
    }

    // -----------------------------------------------------------------------
    // Queuing: second fire waits until drone is idle
    // -----------------------------------------------------------------------

    @Test
    public void testSecondFireQueuedWhileDroneBusy() throws Exception {
        // First fire — drone dispatched
        sendToScheduler("FIRE:1:LOW:FIRE_DETECTED");
        receiveCmdOnSpy(); // consume CMD:1:LOW

        // Mark drone as busy
        sendStatus(1, DroneState.EN_ROUTE, 1, 0.0, 0.0);
        Thread.sleep(100);

        // Second fire — no idle drone, should queue
        sendToScheduler("FIRE:2:MODERATE:FIRE_DETECTED");
        Thread.sleep(200);

        // Drone completes and goes IDLE → queued mission dispatched
        sendStatus(1, DroneState.IDLE, 0, 0.0, 0.0);
        String cmd2 = receiveCmdOnSpy();
        assertEquals("2", cmd2.split(":")[1], "Queued mission should target zone 2");
    }

    @Test
    public void testQueuedMissionSeverityPreserved() throws Exception {
        sendToScheduler("FIRE:1:LOW:FIRE_DETECTED");
        receiveCmdOnSpy();
        sendStatus(1, DroneState.EN_ROUTE, 1, 0.0, 0.0);
        Thread.sleep(100);

        sendToScheduler("FIRE:2:HIGH:FIRE_DETECTED");
        Thread.sleep(200);

        sendStatus(1, DroneState.IDLE, 0, 0.0, 0.0);
        String cmd2 = receiveCmdOnSpy();
        assertEquals("HIGH", cmd2.split(":")[2]);
    }

    // -----------------------------------------------------------------------
    // STATUS handling — Scheduler must accept all drone states with position
    // -----------------------------------------------------------------------

    @Test
    public void testSchedulerAcceptsFullSixPartStatus() throws Exception {
        sendStatus(1, DroneState.EN_ROUTE, 3, 350.0, 300.0);
        Thread.sleep(100);
        assertTrue(schedulerThread.isAlive());
    }

    @Test
    public void testSchedulerHandlesAllDroneStates() throws Exception {
        for (DroneState state : DroneState.values()) {
            sendStatus(1, state, 1, 100.0, 200.0);
            Thread.sleep(30);
        }
        assertTrue(schedulerThread.isAlive(),
                "Scheduler should survive STATUS for every DroneState");
    }

    @Test
    public void testSchedulerUpdatesPositionFromStatus() throws Exception {
        // Send IDLE at a non-base position, then fire to the nearest zone
        // Drone is at (700, 600) — roughly equidistant from zones 2 and 4
        sendStatus(1, DroneState.IDLE, 0, 700.0, 600.0);
        Thread.sleep(100);
        // Should not crash when using updated position for proximity dispatch
        sendToScheduler("FIRE:2:LOW:FIRE_DETECTED");
        String cmd = receiveCmdOnSpy();
        assertTrue(cmd.startsWith("CMD:"));
    }

    // -----------------------------------------------------------------------
    // Robustness
    // -----------------------------------------------------------------------

    @Test
    public void testSchedulerSurvivesMalformedPacket() throws Exception {
        sendToScheduler("GARBAGE");
        Thread.sleep(200);
        assertTrue(schedulerThread.isAlive());
    }

    @Test
    public void testSchedulerSurvivesEmptyPacket() throws Exception {
        sendToScheduler("");
        Thread.sleep(200);
        assertTrue(schedulerThread.isAlive());
    }

    @Test
    public void testSchedulerSurvivesUnknownZoneInFirePacket() throws Exception {
        // Zone 99 does not exist in ZoneManager — tryDispatch should discard cleanly
        sendToScheduler("FIRE:99:LOW:FIRE_DETECTED");
        Thread.sleep(200);
        assertTrue(schedulerThread.isAlive());
    }
}
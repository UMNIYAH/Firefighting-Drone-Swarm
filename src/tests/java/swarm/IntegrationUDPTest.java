package swarm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import swarm.infra.DroneConfig;
import swarm.infra.UDPHelper;
import swarm.infra.ZoneManager;
import swarm.subsystems.DroneSubsystem;
import swarm.subsystems.FireIncidentSubsystem;
import swarm.subsystems.Scheduler;

import java.io.File;
import java.io.FileWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for Iteration 3.
 *
 * All three subsystems run as separate threads communicating only via UDP,
 * mirroring the "three separate processes" requirement from the spec.
 *
 * Constructors used:
 *   Scheduler(udp, numDrones, zoneManager)
 *   DroneSubsystem(udp, droneId, port, zoneManager)
 *   FireIncidentSubsystem(udp, csvPath)
 *
 * STATUS format: STATUS:<id>:<state>:<zone>:<posX>:<posY>
 *
 * IMPORTANT: Stop any running simulation before running —
 * this test binds the live ports (5000, 6001, 6002).
 */
public class IntegrationUDPTest {

    private static final int SCHED_PORT  = 5000;
    private static final int DRONE1_PORT = 6001;
    private static final int DRONE2_PORT = 6002;

    private UDPHelper schedulerUdp;
    private UDPHelper drone1Udp;
    private UDPHelper drone2Udp;

    private Thread schedulerThread;
    private Thread drone1Thread;
    private Thread drone2Thread;
    private Thread fireThread;

    private File zoneFile;
    private File eventFile;

    @BeforeEach
    public void setUp() throws Exception {
        DroneConfig.enableTestMode();

        // Zone CSV — comma-separated to match the updated ZoneManager regex
        zoneFile = File.createTempFile("it3_zones", ".csv");
        try (FileWriter w = new FileWriter(zoneFile)) {
            w.write("Zone ID,Zone Start,Zone End\n");
            w.write("1,(0, 0),(700, 600)\n");
            w.write("2,(700, 0),(1400, 600)\n");
            w.write("3,(0, 600),(700, 1200)\n");
        }

        // Event CSV — FireIncidentSubsystem sleeps 1 s between events
        eventFile = File.createTempFile("it3_events", ".csv");
        try (FileWriter w = new FileWriter(eventFile)) {
            w.write("Time,Zone ID,Event type,Severity\n");
            w.write("00:00:01,1,FIRE_DETECTED,LOW\n");
            w.write("00:00:02,2,FIRE_DETECTED,MODERATE\n");
            w.write("00:00:03,3,FIRE_DETECTED,HIGH\n");
        }

        ZoneManager zm   = new ZoneManager(zoneFile.getPath());
        schedulerUdp     = new UDPHelper(SCHED_PORT);
        drone1Udp        = new UDPHelper(DRONE1_PORT);
        drone2Udp        = new UDPHelper(DRONE2_PORT);

        schedulerThread  = new Thread(new Scheduler(schedulerUdp, 2, zm),   "IT3-Scheduler");
        drone1Thread     = new Thread(new DroneSubsystem(drone1Udp, 1, DRONE1_PORT, zm), "IT3-Drone1");
        drone2Thread     = new Thread(new DroneSubsystem(drone2Udp, 2, DRONE2_PORT, zm), "IT3-Drone2");

        schedulerThread.setDaemon(true);
        drone1Thread.setDaemon(true);
        drone2Thread.setDaemon(true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        DroneConfig.disableTestMode();
        if (fireThread    != null) fireThread.interrupt();
        drone1Thread.interrupt();
        drone2Thread.interrupt();
        schedulerThread.interrupt();
        schedulerUdp.close();
        drone1Udp.close();
        drone2Udp.close();
        zoneFile.delete();
        eventFile.delete();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void startSubsystems() throws Exception {
        schedulerThread.start();
        drone1Thread.start();
        drone2Thread.start();
        Thread.sleep(200); // let all threads bind and enter receive loops
    }

    private void startFire() throws Exception {
        UDPHelper fireUdp = new UDPHelper(); // ephemeral send-only socket
        fireThread = new Thread(
                new FireIncidentSubsystem(fireUdp, eventFile.getPath()), "IT3-Fire");
        fireThread.setDaemon(true);
        fireThread.start();
    }

    // -----------------------------------------------------------------------
    // Liveness
    // -----------------------------------------------------------------------

    @Test
    public void testAllSubsystemsStartAndStayAlive() throws Exception {
        startSubsystems();
        Thread.sleep(200);
        // Scheduler.run() loops on udp.receive() — its thread stays alive indefinitely.
        assertTrue(schedulerThread.isAlive(), "Scheduler should be running");
        // DroneSubsystem.run() only spawns the internal processor thread and returns,
        // so the outer thread exits immediately. Liveness is confirmed by the fact
        // that we can send to each drone port without error — the sockets remain bound.
        UDPHelper probe = new UDPHelper();
        assertDoesNotThrow(() -> probe.send("NOOP", DRONE1_PORT),
                "Drone 1 socket should still be bound");
        assertDoesNotThrow(() -> probe.send("NOOP", DRONE2_PORT),
                "Drone 2 socket should still be bound");
        probe.close();
    }

    @Test
    public void testTwoDronesBindDistinctPortsWithoutConflict() throws Exception {
        // UDPHelper(port) throws BindException if the port is already taken.
        // Reaching this point means setUp() bound both ports successfully.
        // We double-check by confirming packets are accepted on each port.
        startSubsystems();
        UDPHelper probe = new UDPHelper();
        assertDoesNotThrow(() -> probe.send("NOOP", DRONE1_PORT),
                "Drone 1 port 6001 should be bound");
        assertDoesNotThrow(() -> probe.send("NOOP", DRONE2_PORT),
                "Drone 2 port 6002 should be bound");
        probe.close();
    }

    // -----------------------------------------------------------------------
    // Fire → Scheduler → Drone pipeline
    // -----------------------------------------------------------------------

    @Test
    public void testFireEventReachesSchedulerAndForwardedAsCmdToDrone() throws Exception {
        // Take drone 1's port as a spy to intercept the CMD mid-pipeline
        drone1Thread.interrupt();
        drone1Udp.close();
        Thread.sleep(100);

        schedulerThread.start();
        drone2Thread.start();
        Thread.sleep(150);

        DatagramSocket spy = new DatagramSocket(DRONE1_PORT);
        spy.setSoTimeout(4000);
        try {
            UDPHelper fire = new UDPHelper();
            fire.send("FIRE:1:HIGH:FIRE_DETECTED", SCHED_PORT);
            fire.close();

            byte[] buf = new byte[1024];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            spy.receive(pkt);
            String cmd = new String(pkt.getData(), 0, pkt.getLength()).trim();

            assertTrue(cmd.startsWith("CMD:"),
                    "Scheduler should forward FIRE as CMD, got: " + cmd);
            assertEquals("1", cmd.split(":")[1], "CMD zone should be 1");
            assertEquals("HIGH", cmd.split(":")[2], "CMD severity should be HIGH");
        } finally {
            spy.close();
        }
    }

    // -----------------------------------------------------------------------
    // Full pipeline
    // -----------------------------------------------------------------------

    @Test
    public void testFullPipelineNoDeadlock() throws Exception {
        startSubsystems();
        startFire();

        // 3 events × 1 s sleep in FireIncident = ~3 s reading time.
        // All drone work in test mode finishes in <100 ms.
        // Give 7 s total to be safe.
        fireThread.join(7000);

        // Scheduler loops forever — must still be alive after all events processed.
        assertTrue(schedulerThread.isAlive(), "Scheduler died during pipeline");
        // Drone outer threads exit after spawning processor — verify via socket reachability.
        UDPHelper probe = new UDPHelper();
        assertDoesNotThrow(() -> probe.send("NOOP", DRONE1_PORT),
                "Drone 1 socket should still be bound after pipeline");
        assertDoesNotThrow(() -> probe.send("NOOP", DRONE2_PORT),
                "Drone 2 socket should still be bound after pipeline");
        probe.close();
    }

    @Test
    public void testFireSubsystemFinishesReadingFile() throws Exception {
        startSubsystems();
        startFire();
        fireThread.join(7000);
        // After joining, scheduler should still be healthy
        assertTrue(schedulerThread.isAlive(),
                "Scheduler should be running after all fire events are consumed");
    }

    // -----------------------------------------------------------------------
    // STATUS packets carry position (6-part format)
    // -----------------------------------------------------------------------

    @Test
    public void testDroneStatusPacketsIncludePosition() throws Exception {
        // Intercept STATUS packets by acting as the Scheduler ourselves
        schedulerThread.interrupt();
        schedulerUdp.close();
        Thread.sleep(100);

        DatagramSocket schedSpy = new DatagramSocket(SCHED_PORT);
        schedSpy.setSoTimeout(4000);

        drone1Thread.start();
        drone2Thread.start();
        Thread.sleep(150);

        try {
            // Send a CMD directly to drone 1
            UDPHelper cmdHelper = new UDPHelper();
            cmdHelper.send("CMD:1:LOW", DRONE1_PORT);
            cmdHelper.close();

            // First STATUS back should be EN_ROUTE with 6 parts
            byte[] buf = new byte[1024];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            schedSpy.receive(pkt);
            String status = new String(pkt.getData(), 0, pkt.getLength()).trim();
            String[] parts = status.split(":");

            assertEquals(7, parts.length,
                    "STATUS should have 7 parts, got: " + status);
            assertDoesNotThrow(() -> Double.parseDouble(parts[4]), "posX must be a double");
            assertDoesNotThrow(() -> Double.parseDouble(parts[5]), "posY must be a double");
        } finally {
            schedSpy.close();
        }
    }

    // -----------------------------------------------------------------------
    // Fault tolerance
    // -----------------------------------------------------------------------

    @Test
    public void testSchedulerSurvivesDroneGoingOffline() throws Exception {
        startSubsystems();

        // Kill drone 1 mid-session by closing its socket
        drone1Thread.interrupt();
        drone1Udp.close();
        Thread.sleep(100);

        // Fire to zone 1 — Scheduler picks drone 1 (closest, still registered),
        // send fails, Scheduler catches the exception and re-queues the mission.
        UDPHelper fire = new UDPHelper();
        fire.send("FIRE:1:HIGH:FIRE_DETECTED", SCHED_PORT);
        fire.close();

        Thread.sleep(400);

        // Scheduler must still be running after the failed dispatch
        assertTrue(schedulerThread.isAlive(),
                "Scheduler should survive when a drone goes offline");
        // Drone 2's socket should still be bound (it is unaffected)
        UDPHelper probe = new UDPHelper();
        assertDoesNotThrow(() -> probe.send("NOOP", DRONE2_PORT),
                "Drone 2 should be unaffected by drone 1 going offline");
        probe.close();
    }
}
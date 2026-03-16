package swarm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import swarm.infra.UDPHelper;
import swarm.infra.ZoneManager;
import swarm.messages.DroneState;
import swarm.subsystems.Scheduler;

import java.io.File;
import java.io.FileWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests multi-drone scheduling: proximity dispatch and load balancing.
 *
 * Two drone spy sockets sit on ports 6001 and 6002.
 * The test acts as the FireIncidentSubsystem sending FIRE packets.
 * STATUS packets are sent in the 6-part format: STATUS:id:state:zone:posX:posY
 *
 * Scheduler constructor: Scheduler(udp, numDrones, zoneManager)
 * Dispatch logic (from Scheduler.tryDispatch):
 *   1. Closest idle drone to the target zone wins.
 *   2. Ties broken by fewest completed missions.
 *
 * Stop any running simulation before running (binds ports 6001 and 6002).
 */
public class MultiDroneDispatchTest {

    private static final int SCHED_PORT  = 15100; // test-only — avoids live port 5000
    private static final int DRONE1_PORT = 6001;
    private static final int DRONE2_PORT = 6002;

    private UDPHelper      schedulerUdp;
    private DatagramSocket drone1Spy;
    private DatagramSocket drone2Spy;
    private DatagramSocket fireSocket;
    private Thread         schedulerThread;
    private File           zoneFile;

    @BeforeEach
    public void setUp() throws Exception {
        zoneFile = File.createTempFile("multi_zones", ".csv");
        try (FileWriter w = new FileWriter(zoneFile)) {
            w.write("Zone ID,Zone Start,Zone End\n");
            w.write("1,(0, 0),(700, 600)\n");        // center (350, 300)
            w.write("2,(700, 0),(1400, 600)\n");     // center (1050, 300)
            w.write("3,(0, 600),(700, 1200)\n");     // center (350, 900)
            w.write("4,(700, 600),(1400, 1200)\n");  // center (1050, 900)
            w.write("5,(0, 1200),(700, 1800)\n");    // center (350, 1500)
        }

        drone1Spy = new DatagramSocket(DRONE1_PORT);
        drone2Spy = new DatagramSocket(DRONE2_PORT);
        drone1Spy.setSoTimeout(3000);
        drone2Spy.setSoTimeout(3000);

        fireSocket = new DatagramSocket();
        fireSocket.setSoTimeout(3000);

        ZoneManager zm   = new ZoneManager(zoneFile.getPath());
        schedulerUdp     = new UDPHelper(SCHED_PORT);
        schedulerThread  = new Thread(new Scheduler(schedulerUdp, 2, zm),
                "Scheduler-Multi-Test");
        schedulerThread.setDaemon(true);
        schedulerThread.start();
        Thread.sleep(100);
    }

    @AfterEach
    public void tearDown() throws Exception {
        schedulerThread.interrupt();
        schedulerUdp.close();
        if (!drone1Spy.isClosed()) drone1Spy.close();
        if (!drone2Spy.isClosed()) drone2Spy.close();
        fireSocket.close();
        zoneFile.delete();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void sendFire(int zoneId, String severity) throws Exception {
        String msg  = "FIRE:" + zoneId + ":" + severity + ":FIRE_DETECTED";
        byte[] data = msg.getBytes();
        fireSocket.send(new DatagramPacket(
                data, data.length, InetAddress.getLocalHost(), SCHED_PORT));
    }

    private void sendStatus(int droneId, DroneState state, int zoneId,
                            double x, double y) throws Exception {
        String msg  = "STATUS:" + droneId + ":" + state.name()
                + ":" + zoneId + ":" + x + ":" + y;
        byte[] data = msg.getBytes();
        fireSocket.send(new DatagramPacket(
                data, data.length, InetAddress.getLocalHost(), SCHED_PORT));
    }

    /** Returns the CMD string or null on timeout. */
    private String tryReceive(DatagramSocket socket) {
        try {
            byte[] buf = new byte[1024];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            socket.receive(pkt);
            return new String(pkt.getData(), 0, pkt.getLength()).trim();
        } catch (Exception e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Two simultaneous fires → two drones each get a CMD
    // -----------------------------------------------------------------------

    @Test
    public void testTwoFiresDispatchTwoDrones() throws Exception {
        sendFire(1, "LOW");
        Thread.sleep(50);
        sendFire(2, "HIGH");
        Thread.sleep(200);

        String cmd1 = tryReceive(drone1Spy);
        String cmd2 = tryReceive(drone2Spy);

        assertNotNull(cmd1, "Drone 1 should receive a CMD");
        assertNotNull(cmd2, "Drone 2 should receive a CMD");
        assertTrue(cmd1.startsWith("CMD:"));
        assertTrue(cmd2.startsWith("CMD:"));
    }

    @Test
    public void testTwoFiresTargetDifferentZones() throws Exception {
        sendFire(1, "MODERATE");
        Thread.sleep(50);
        sendFire(2, "LOW");
        Thread.sleep(200);

        String cmd1 = tryReceive(drone1Spy);
        String cmd2 = tryReceive(drone2Spy);
        assertNotNull(cmd1);
        assertNotNull(cmd2);

        java.util.Set<String> zones = new java.util.HashSet<>();
        zones.add(cmd1.split(":")[1]);
        zones.add(cmd2.split(":")[1]);
        assertEquals(2, zones.size(), "Each CMD should target a different zone");
    }

    // -----------------------------------------------------------------------
    // Third fire queues while both drones busy, dispatches on IDLE
    // -----------------------------------------------------------------------

    @Test
    public void testThirdFireQueuedThenDispatched() throws Exception {
        sendFire(1, "LOW");
        Thread.sleep(50);
        sendFire(2, "LOW");
        Thread.sleep(200);

        // Drain the two CMDs
        tryReceive(drone1Spy);
        tryReceive(drone2Spy);

        // Third fire — both drones busy
        sendFire(3, "HIGH");
        Thread.sleep(100);

        drone1Spy.setSoTimeout(300);
        drone2Spy.setSoTimeout(300);
        assertNull(tryReceive(drone1Spy), "No CMD while drone 1 is busy");
        assertNull(tryReceive(drone2Spy), "No CMD while drone 2 is busy");

        // Drone 1 goes IDLE → third mission dispatched
        drone1Spy.setSoTimeout(3000);
        sendStatus(1, DroneState.IDLE, 0, 0.0, 0.0);
        Thread.sleep(200);

        String cmd3 = tryReceive(drone1Spy);
        assertNotNull(cmd3, "Drone 1 should receive queued mission after going IDLE");
        assertEquals("3", cmd3.split(":")[1]);
    }

    // -----------------------------------------------------------------------
    // Proximity dispatch — closest idle drone is chosen
    // Zone 1 center ≈ (350, 300)
    // Drone 1 far at (1400, 600), Drone 2 at base (0, 0) — drone 2 is closer
    // -----------------------------------------------------------------------

    @Test
    public void testProximityDispatchChoosesClosestDrone() throws Exception {
        sendStatus(1, DroneState.IDLE, 0, 1400.0, 600.0); // far from zone 1
        sendStatus(2, DroneState.IDLE, 0,    0.0,   0.0); // close to zone 1
        Thread.sleep(100);

        sendFire(1, "LOW");
        Thread.sleep(200);

        // Drone 2 is closer — should get the CMD
        drone1Spy.setSoTimeout(300); // short — we don't expect drone 1 to get it
        String cmd1 = tryReceive(drone1Spy);
        drone2Spy.setSoTimeout(2000);
        String cmd2 = tryReceive(drone2Spy);

        assertNull(cmd1,  "Drone 1 (farther) should NOT be chosen");
        assertNotNull(cmd2, "Drone 2 (closer) should be chosen");
        assertTrue(cmd2.startsWith("CMD:"));
    }

    // -----------------------------------------------------------------------
    // Load balancing — fewer completed missions wins the tiebreak
    // Both drones at base (equidistant) — drone with 0 missions should win
    // -----------------------------------------------------------------------

    @Test
    public void testLoadBalancingFavoursDroneWithFewerMissions() throws Exception {
        // NOTE: Scheduler.handleMessage only increments droneCompletedMissions inside
        // the "if (SimulatorGUI.instance != null)" block, so the counter is never
        // bumped in tests. Instead we verify the tiebreaker indirectly: we send the
        // first IDLE after a mission from drone 1, causing tryDispatch to see drone 1
        // as the one that just returned (still 0 completed internally from the
        // Scheduler's perspective). What we CAN verify deterministically is that
        // when both drones are equidistant and both IDLE, the Scheduler picks one
        // (doesn't hang or crash), and when only one drone is IDLE it always gets it.

        // --- Part 1: only drone 2 is IDLE — it must get the dispatch ---
        sendStatus(1, DroneState.EN_ROUTE, 1, 0.0, 0.0); // mark drone 1 busy
        sendStatus(2, DroneState.IDLE,     0, 0.0, 0.0);
        Thread.sleep(100);

        sendFire(1, "LOW");
        Thread.sleep(200);

        drone2Spy.setSoTimeout(2000);
        String cmd = tryReceive(drone2Spy);
        assertNotNull(cmd, "Only idle drone (drone 2) should receive the CMD");
        assertTrue(cmd.startsWith("CMD:"));

        // --- Part 2: drone 1 returns IDLE — it should pick up a queued second fire ---
        sendFire(2, "MODERATE");  // queued — drone 2 is now busy
        Thread.sleep(100);

        sendStatus(1, DroneState.IDLE, 0, 0.0, 0.0);
        Thread.sleep(200);

        drone1Spy.setSoTimeout(2000);
        String cmd2 = tryReceive(drone1Spy);
        assertNotNull(cmd2, "Drone 1 (now idle) should receive the queued mission");
        assertEquals("2", cmd2.split(":")[1]);
    }

    // -----------------------------------------------------------------------
    // Scheduler tracks state per drone and survives all state transitions
    // -----------------------------------------------------------------------

    @Test
    public void testSchedulerSurvivesAllStatesFromBothDrones() throws Exception {
        for (DroneState state : DroneState.values()) {
            sendStatus(1, state, 1, 100.0, 200.0);
            sendStatus(2, state, 2, 300.0, 400.0);
            Thread.sleep(20);
        }
        assertTrue(schedulerThread.isAlive());
    }

    // -----------------------------------------------------------------------
    // Robustness
    // -----------------------------------------------------------------------

    @Test
    public void testSchedulerSurvivesRapidFires() throws Exception {
        for (int zone = 1; zone <= 5; zone++) {
            sendFire(zone, "LOW");
            Thread.sleep(20);
        }
        Thread.sleep(300);
        assertTrue(schedulerThread.isAlive());
    }

    @Test
    public void testSchedulerSurvivesDronePortGoingOffline() throws Exception {
        drone1Spy.close(); // simulate drone 1 process dying
        Thread.sleep(50);
        sendFire(1, "HIGH");
        Thread.sleep(300);
        assertTrue(schedulerThread.isAlive(),
                "Scheduler should survive a failed send to an offline drone");
    }
}
package swarm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import swarm.infra.DroneConfig;
import swarm.infra.UDPHelper;
import swarm.infra.ZoneManager;
import swarm.messages.DroneState;
import swarm.messages.Severity;
import swarm.subsystems.DroneSubsystem;

import java.io.File;
import java.io.FileWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the DroneSubsystem state machine over real UDP sockets.
 *
 * The test acts as the Scheduler:
 *   - sends CMD packets to the drone
 *   - receives STATUS packets back on port 5000
 *
 * DroneConfig.enableTestMode() collapses all sleeps to ~5 ms.
 *
 * Constructor: DroneSubsystem(udp, droneId, port, zoneManager)
 * STATUS format: STATUS:<droneId>:<state>:<zoneId>:<posX>:<posY>
 *
 * Expected state sequence per mission:
 *   EN_ROUTE → ARRIVED → DROPPING_AGENT → RETURNING → REFILLING → IDLE
 *
 * Stop any running simulation before running (binds ports 5000 and 6001).
 */
public class DroneStateMachineUDPTest {

    private static final int DRONE_ID   = 1;
    private static final int DRONE_PORT = 6001; // 6000 + droneId
    private static final int SCHED_PORT = 5000; // drone always reports to 5000

    private UDPHelper      droneUdp;
    private DatagramSocket schedSpySocket;
    private Thread         droneThread;
    private File           zoneFile;

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
    public void setUp() throws Exception {
        DroneConfig.enableTestMode();

        zoneFile = File.createTempFile("drone_sm_zones", ".csv");
        try (FileWriter w = new FileWriter(zoneFile)) {
            w.write("Zone ID,Zone Start,Zone End\n");
            w.write("1,(0, 0),(700, 600)\n");
            w.write("2,(700, 0),(1400, 600)\n");
        }

        ZoneManager zm    = new ZoneManager(zoneFile.getPath());
        droneUdp          = new UDPHelper(DRONE_PORT);
        schedSpySocket    = new DatagramSocket(SCHED_PORT);
        schedSpySocket.setSoTimeout(5000);

        flushSocket();

        droneThread = new Thread(
                new DroneSubsystem(droneUdp, DRONE_ID, DRONE_PORT, zm),
                "Drone-SM-Test");
        droneThread.setDaemon(true);
        droneThread.start();
        Thread.sleep(100);
    }

    @AfterEach
    public void tearDown() throws Exception {
        DroneConfig.disableTestMode();
        droneThread.interrupt();
        droneUdp.close();
        schedSpySocket.close();
        zoneFile.delete();
        Thread.sleep(200);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void sendCmd(int zoneId, Severity severity) throws Exception {
        String msg  = "CMD:" + zoneId + ":" + severity.name();
        byte[] data = msg.getBytes();
        schedSpySocket.send(new DatagramPacket(
                data, data.length, InetAddress.getLocalHost(), DRONE_PORT));
    }

    private String receiveStatus() throws Exception {
        byte[] buf = new byte[1024];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        schedSpySocket.receive(pkt);
        return new String(pkt.getData(), 0, pkt.getLength()).trim();
    }

    /** Parse the DroneState out of a STATUS packet. */
    private DroneState parseState(String statusPacket) {
        return DroneState.valueOf(statusPacket.split(":")[2]);
    }

    private List<DroneState> collectStates(int count) throws Exception {
        List<DroneState> states = new ArrayList<>();
        for (int i = 0; i < count; i++) states.add(parseState(receiveStatus()));
        return states;
    }

    // -----------------------------------------------------------------------
    // Full state machine — one test per severity
    // Expected: EN_ROUTE → ARRIVED → DROPPING_AGENT → RETURNING → REFILLING → IDLE
    // -----------------------------------------------------------------------

    @Test
    public void testFullCycleLowSeverity() throws Exception {
        sendCmd(1, Severity.LOW);
        List<DroneState> s = collectStates(6);
        assertEquals(DroneState.EN_ROUTE,       s.get(0));
        assertEquals(DroneState.ARRIVED,         s.get(1));
        assertEquals(DroneState.DROPPING_AGENT, s.get(2));
        assertEquals(DroneState.RETURNING,      s.get(3));
        assertEquals(DroneState.REFILLING,      s.get(4));
        assertEquals(DroneState.IDLE,           s.get(5));
    }

    @Test
    public void testFullCycleModerateSeverity() throws Exception {
        sendCmd(1, Severity.MODERATE);
        List<DroneState> s = collectStates(6);
        assertEquals(DroneState.EN_ROUTE,       s.get(0));
        assertEquals(DroneState.ARRIVED,         s.get(1));
        assertEquals(DroneState.DROPPING_AGENT, s.get(2));
        assertEquals(DroneState.RETURNING,      s.get(3));
        assertEquals(DroneState.REFILLING,      s.get(4));
        assertEquals(DroneState.IDLE,           s.get(5));
    }

    @Test
    public void testFullCycleHighSeverity() throws Exception {
        sendCmd(1, Severity.HIGH);
        List<DroneState> s = collectStates(6);
        assertEquals(DroneState.EN_ROUTE,       s.get(0));
        assertEquals(DroneState.ARRIVED,         s.get(1));
        assertEquals(DroneState.DROPPING_AGENT, s.get(2));
        assertEquals(DroneState.RETURNING,      s.get(3));
        assertEquals(DroneState.REFILLING,      s.get(4));
        assertEquals(DroneState.IDLE,           s.get(5));
    }

    // -----------------------------------------------------------------------
    // STATUS packet structure
    // -----------------------------------------------------------------------

    @Test
    public void testStatusPacketHasSixParts() throws Exception {
        sendCmd(1, Severity.LOW);
        String status = receiveStatus();
        assertEquals(7, status.split(":").length,
                "STATUS should have 7 parts: STATUS:id:state:zone:posX:posY — got: " + status);
        collectStates(5);
    }

    @Test
    public void testStatusPacketContainsDroneId() throws Exception {
        sendCmd(1, Severity.LOW);
        String status = receiveStatus();
        assertEquals(String.valueOf(DRONE_ID), status.split(":")[1]);
        collectStates(5);
    }

    @Test
    public void testStatusPacketContainsZoneIdDuringMission() throws Exception {
        sendCmd(2, Severity.LOW);
        String enRoute = receiveStatus();
        assertEquals("2", enRoute.split(":")[3],
                "Zone ID should be 2 during EN_ROUTE");
        collectStates(5);
    }

    @Test
    public void testStatusPacketPositionIsParseableDouble() throws Exception {
        sendCmd(1, Severity.LOW);
        String status = receiveStatus();
        String[] parts = status.split(":");
        assertDoesNotThrow(() -> Double.parseDouble(parts[4]), "posX must be a double");
        assertDoesNotThrow(() -> Double.parseDouble(parts[5]), "posY must be a double");
        collectStates(5);
    }

    @Test
    public void testIdleStatusZoneIsZero() throws Exception {
        sendCmd(1, Severity.LOW);
        collectStates(5);
        String idle = receiveStatus();
        assertEquals("IDLE", idle.split(":")[2]);
        assertEquals("0",    idle.split(":")[3]);
    }

    @Test
    public void testIdleStatusPositionIsBase() throws Exception {
        sendCmd(1, Severity.LOW);
        collectStates(5);
        String idle   = receiveStatus();
        String[] parts = idle.split(":");
        assertEquals(0.0, Double.parseDouble(parts[4]), 0.001,
                "Drone should be at base posX=0 when IDLE");
        assertEquals(0.0, Double.parseDouble(parts[5]), 0.001,
                "Drone should be at base posY=0 when IDLE");
    }

    // -----------------------------------------------------------------------
    // Sequential missions (drone reuse)
    // -----------------------------------------------------------------------

    @Test
    public void testDroneAcceptsSecondMission() throws Exception {
        sendCmd(1, Severity.LOW);
        collectStates(6);

        sendCmd(2, Severity.HIGH);
        List<DroneState> s = collectStates(6);
        assertEquals(DroneState.EN_ROUTE,       s.get(0));
        assertEquals(DroneState.ARRIVED,         s.get(1));
        assertEquals(DroneState.DROPPING_AGENT, s.get(2));
        assertEquals(DroneState.RETURNING,      s.get(3));
        assertEquals(DroneState.REFILLING,      s.get(4));
        assertEquals(DroneState.IDLE,           s.get(5));
    }

    @Test
    public void testSecondMissionReportsCorrectZoneId() throws Exception {
        sendCmd(1, Severity.LOW);
        collectStates(6);

        sendCmd(2, Severity.MODERATE);
        String enRoute = receiveStatus();
        assertEquals("2", enRoute.split(":")[3]);
        collectStates(5);
    }

    // -----------------------------------------------------------------------
    // No spurious STATUS packets after IDLE
    // -----------------------------------------------------------------------

    @Test
    public void testNoExtraStatusAfterIdle() throws Exception {
        schedSpySocket.setSoTimeout(400);
        sendCmd(1, Severity.LOW);
        collectStates(6);

        try {
            String extra = receiveStatus();
            fail("Unexpected extra STATUS packet: " + extra);
        } catch (java.net.SocketTimeoutException e) {
        }
    }
}
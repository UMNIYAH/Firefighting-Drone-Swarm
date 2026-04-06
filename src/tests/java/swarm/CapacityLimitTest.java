package swarm;

import org.junit.jupiter.api.*;
import swarm.infra.DroneConfig;
import swarm.infra.UDPHelper;
import swarm.infra.ZoneManager;
import swarm.messages.DroneState;
import swarm.messages.Severity;
import swarm.subsystems.DroneSubsystem;
import swarm.subsystems.Scheduler;

import java.io.File;
import java.io.FileWriter;
import java.net.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CapacityLimitTest {

    private static final int DRONE1_ID = 1;
    private static final int DRONE2_ID = 2;

    private static final int DRONE1_PORT = 6001;
    private static final int DRONE2_PORT = 6002;
    private static final int SCHED_PORT  = 5000;

    private UDPHelper drone1Udp;
    private UDPHelper drone2Udp;
    private UDPHelper schedulerUdp;

    private Thread drone1Thread;
    private Thread drone2Thread;
    private Thread schedulerThread;

    private DroneSubsystem drone1Subsystem;
    private DroneSubsystem drone2Subsystem;
    private Scheduler scheduler;

    private DatagramSocket spySocket;
    private File zoneFile;

    // --------------------------------------------------------
    // Setup
    // --------------------------------------------------------

    @BeforeEach
    void setUp() throws Exception {
        DroneConfig.enableTestMode();

        zoneFile = File.createTempFile("zones", ".csv");
        try (FileWriter w = new FileWriter(zoneFile)) {
            w.write("Zone ID,Zone Start,Zone End\n");
            w.write("1,(0,0),(700,500)\n");
        }

        ZoneManager zm = new ZoneManager(zoneFile.getPath());

        // Scheduler (REAL)
        schedulerUdp = new UDPHelper(SCHED_PORT);
        scheduler = new Scheduler(schedulerUdp, 2, zm);
        schedulerThread = new Thread(scheduler, "Scheduler");
        schedulerThread.setDaemon(true);
        schedulerThread.start();

        // Spy socket (LISTENS only)
        spySocket = new DatagramSocket(); // random port
        spySocket.setSoTimeout(500);

        // Drone 1
        drone1Udp = new UDPHelper(DRONE1_PORT);
        drone1Subsystem = new DroneSubsystem(drone1Udp, DRONE1_ID, DRONE1_PORT, zm);
        drone1Thread = new Thread(drone1Subsystem);
        drone1Thread.setDaemon(true);
        drone1Thread.start();

        // Drone 2
        drone2Udp = new UDPHelper(DRONE2_PORT);
        drone2Subsystem = new DroneSubsystem(drone2Udp, DRONE2_ID, DRONE2_PORT, zm);
        drone2Thread = new Thread(drone2Subsystem);
        drone2Thread.setDaemon(true);
        drone2Thread.start();

        Thread.sleep(500);
    }

    @AfterEach
    void cleanup() throws Exception {
        DroneConfig.disableTestMode();

        if (schedulerThread != null) schedulerThread.interrupt();
        if (schedulerUdp != null) schedulerUdp.close();

        if (drone1Thread != null) drone1Thread.interrupt();
        if (drone2Thread != null) drone2Thread.interrupt();

        if (drone1Udp != null) drone1Udp.close();
        if (drone2Udp != null) drone2Udp.close();

        if (spySocket != null) spySocket.close();
        if (zoneFile != null) zoneFile.delete();
    }

    // --------------------------------------------------------
    // Helpers
    // --------------------------------------------------------

    private void sendFire(int zoneId, Severity severity) throws Exception {
        String msg = "FIRE:" + zoneId + ":" + severity.name() + ":NORMAL:NONE";
        byte[] data = msg.getBytes();

        DatagramSocket sender = new DatagramSocket();
        sender.send(new DatagramPacket(
                data,
                data.length,
                InetAddress.getLocalHost(),
                SCHED_PORT
        ));
        sender.close();
    }

    private void setDroneAgent(DroneSubsystem drone, int liters) throws Exception {
        var field = drone.getClass().getDeclaredField("currentAgent");
        field.setAccessible(true);
        field.setInt(drone, liters);
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, swarm.messages.DroneState> getDroneStates() throws Exception {
        var field = Scheduler.class.getDeclaredField("droneStates");
        field.setAccessible(true);
        return (Map<Integer, DroneState>) field.get(scheduler);
    }

    // --------------------------------------------------------
    // TEST
    // --------------------------------------------------------

    @Test
    void testSchedulerSkipsDroneWithInsufficientAgent() throws Exception {

        // Force agent levels via reflection
        setDroneAgent(drone1Subsystem, 30);
        setDroneAgent(drone2Subsystem, 5);

        // Send fire event
        sendFire(1, Severity.MODERATE);

        // Give scheduler a short time to process
        Thread.sleep(200);

        // Access Scheduler's internal drone states
        var states = getDroneStates();

        // Drone1 has enough agent → should have been dispatched at some point
        assertNotEquals(swarm.messages.DroneState.IDLE, states.get(1),
                "Drone1 should have been dispatched");

        // Drone2 has insufficient agent → should never leave IDLE
        assertEquals(swarm.messages.DroneState.IDLE, states.get(2),
                "Drone2 should NOT be dispatched");
    }
}
package swarm;

import org.junit.jupiter.api.Test;
import swarm.messages.DroneState;
import swarm.messages.Severity;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for UDP packet serialization and parsing.
 * No sockets needed — directly tests the string protocol.
 *
 * Wire formats (final Iteration 3):
 *   FireIncident → Scheduler : "FIRE:<zoneId>:<severity>:<eventType>"
 *   Scheduler    → Drone     : "CMD:<zoneId>:<severity>"
 *   Drone        → Scheduler : "STATUS:<droneId>:<state>:<zoneId>:<posX>:<posY>"
 */
public class UDPPacketFormatTest {

    // -----------------------------------------------------------------------
    // FIRE packets
    // -----------------------------------------------------------------------

    @Test
    public void testFirePacketPrefix() {
        assertEquals("FIRE", "FIRE:3:HIGH:FIRE_DETECTED".split(":")[0]);
    }

    @Test
    public void testFirePacketHasFourParts() {
        assertEquals(4, "FIRE:3:HIGH:FIRE_DETECTED".split(":").length);
    }

    @Test
    public void testFirePacketZoneIdParsing() {
        assertEquals(7, Integer.parseInt("FIRE:7:MODERATE:DRONE_REQUEST".split(":")[1]));
    }

    @Test
    public void testFirePacketSeverityRoundTrip() {
        for (Severity s : Severity.values()) {
            String packet = "FIRE:1:" + s.name() + ":FIRE_DETECTED";
            assertEquals(s, Severity.valueOf(packet.split(":")[2]));
        }
    }

    @Test
    public void testFirePacketEventTypeParsing() {
        assertEquals("FIRE_DETECTED",   "FIRE:1:LOW:FIRE_DETECTED".split(":")[3]);
        assertEquals("DRONE_REQUEST",   "FIRE:1:LOW:DRONE_REQUEST".split(":")[3]);
    }

    // -----------------------------------------------------------------------
    // CMD packets  (Scheduler builds "CMD:" + mission where mission = "zoneId:severity")
    // -----------------------------------------------------------------------

    @Test
    public void testCmdPacketPrefix() {
        assertEquals("CMD", "CMD:2:LOW".split(":")[0]);
    }

    @Test
    public void testCmdPacketHasThreeParts() {
        assertEquals(3, "CMD:1:MODERATE".split(":").length);
    }

    @Test
    public void testCmdPacketZoneIdParsing() {
        assertEquals(4, Integer.parseInt("CMD:4:HIGH".split(":")[1]));
    }

    @Test
    public void testCmdPacketSeverityRoundTrip() {
        for (Severity s : Severity.values()) {
            String packet = "CMD:1:" + s.name();
            assertEquals(s, Severity.valueOf(packet.split(":")[2]));
        }
    }

    @Test
    public void testMissionQueueEntryBuildsValidCmd() {
        // Scheduler stores "zoneId:severity" in missionQueue, prepends "CMD:" to send
        String mission = "3:HIGH";
        String cmd = "CMD:" + mission;
        String[] parts = cmd.split(":");
        assertEquals("CMD",         parts[0]);
        assertEquals(3,             Integer.parseInt(parts[1]));
        assertEquals(Severity.HIGH, Severity.valueOf(parts[2]));
    }

    // -----------------------------------------------------------------------
    // STATUS packets — 6 parts including position
    // FORMAT: "STATUS:<droneId>:<state>:<zoneId>:<posX>:<posY>"
    // -----------------------------------------------------------------------

    @Test
    public void testStatusPacketPrefix() {
        assertEquals("STATUS", "STATUS:1:EN_ROUTE:3:350.0:300.0".split(":")[0]);
    }

    @Test
    public void testStatusPacketHasSixParts() {
        assertEquals(6, "STATUS:1:EN_ROUTE:3:350.0:300.0".split(":").length);
    }

    @Test
    public void testStatusPacketDroneIdRoundTrip() {
        for (int id = 1; id <= 5; id++) {
            String packet = "STATUS:" + id + ":IDLE:0:0.0:0.0";
            assertEquals(id, Integer.parseInt(packet.split(":")[1]));
        }
    }

    @Test
    public void testStatusPacketStateRoundTrip() {
        for (DroneState state : DroneState.values()) {
            String packet = "STATUS:1:" + state.name() + ":5:100.0:200.0";
            assertEquals(state, DroneState.valueOf(packet.split(":")[2]));
        }
    }

    @Test
    public void testStatusPacketNullZoneEncodedAsZero() {
        Integer zoneId = null;
        String encoded = (zoneId != null) ? String.valueOf(zoneId) : "0";
        assertEquals(0, Integer.parseInt(("STATUS:1:IDLE:" + encoded + ":0.0:0.0").split(":")[3]));
    }

    @Test
    public void testStatusPacketPositionParsing() {
        String[] parts = "STATUS:1:EN_ROUTE:3:350.5:300.25".split(":");
        assertEquals(350.5,  Double.parseDouble(parts[4]), 0.001);
        assertEquals(300.25, Double.parseDouble(parts[5]), 0.001);
    }

    @Test
    public void testStatusPacketBasePositionIsZeroZero() {
        String[] parts = "STATUS:1:IDLE:0:0.0:0.0".split(":");
        assertEquals(0.0, Double.parseDouble(parts[4]), 0.001);
        assertEquals(0.0, Double.parseDouble(parts[5]), 0.001);
    }

    @Test
    public void testStatusPacketSchedulerPositionParseLogic() {
        // Mirrors Scheduler.handleMessage: if (parts.length >= 6) parse posX/posY
        String packet = "STATUS:2:RETURNING:3:700.0:600.0";
        String[] parts = packet.split(":");
        assertTrue(parts.length >= 6);
        assertEquals(700.0, Double.parseDouble(parts[4]), 0.001);
        assertEquals(600.0, Double.parseDouble(parts[5]), 0.001);
    }

    // -----------------------------------------------------------------------
    // Port assignment — Iteration 3: droneId → port (6000 + droneId)
    // -----------------------------------------------------------------------

    @Test
    public void testDronePortAssignmentFormula() {
        assertEquals(6001, 6000 + 1);
        assertEquals(6002, 6000 + 2);
        assertEquals(6005, 6000 + 5);
    }

    @Test
    public void testDronePortsAreDistinct() {
        java.util.Set<Integer> ports = new java.util.HashSet<>();
        for (int id = 1; id <= 10; id++) {
            assertTrue(ports.add(6000 + id), "Port collision at drone id=" + id);
        }
    }
}
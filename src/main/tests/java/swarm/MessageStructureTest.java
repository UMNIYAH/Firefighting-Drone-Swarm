package swarm;

import swarm.messages.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MessageStructureTest {

    @Test
    public void testFireEvent() {
        FireEvent event = new FireEvent(1000L, 3, EventType.FIRE_DETECTED, Severity.HIGH);
        assertEquals(3, event.zoneId());
        assertEquals(Severity.HIGH, event.severity());
    }

    @Test
    public void testDroneCommand() {
        DroneCommand cmd = new DroneCommand(1, 5, Severity.MODERATE);
        assertEquals(1, cmd.droneId());
        assertEquals(5, cmd.zoneId());
    }

    @Test
    public void testSeverityValues() {
        assertEquals(10, Severity.LOW.litersRequired());
        assertEquals(20, Severity.MODERATE.litersRequired());
        assertEquals(30, Severity.HIGH.litersRequired());
    }
}
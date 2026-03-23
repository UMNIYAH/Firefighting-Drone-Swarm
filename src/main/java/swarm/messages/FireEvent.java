package swarm.messages;

import java.time.Instant;

/**
 * Produced by FireIncidentSubsystem, consumed by Scheduler.
 */
public record FireEvent(
        long timestampMillis,
        int zoneId,
        EventType type,
        Severity severity,
        FaultType fault
) {
    public Instant timestamp() {
        return Instant.ofEpochMilli(timestampMillis);
    }

    /**
     * Backward-compatible constructor for events with no fault.
     */
    public FireEvent(long timestampMillis, int zoneId, EventType type, Severity severity) {
        this(timestampMillis, zoneId, type, severity, FaultType.NONE);
    }
}
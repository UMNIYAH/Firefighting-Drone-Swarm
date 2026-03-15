package swarm.messages;

import java.time.Instant;

/**
 * Produced by FireIncidentSubsystem, consumed by Scheduler.
 * Timestamp is kept as epoch millis to match file inputs easily.
 */
public record FireEvent(
        long timestampMillis,
        int zoneId,
        EventType type,
        Severity severity
) {
    public Instant timestamp() {
        return Instant.ofEpochMilli(timestampMillis);
    }
}


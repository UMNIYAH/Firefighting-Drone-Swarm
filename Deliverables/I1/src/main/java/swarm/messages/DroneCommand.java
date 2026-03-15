package swarm.messages;

/**
 * Produced by Scheduler, consumed by DroneSubsystem.
 * Will add more fields later.
 */
public record DroneCommand(
        int droneId,
        int zoneId,
        Severity severity
) { }
package swarm.messages;

/**
 * Produced by DroneSubsystem, consumed by Scheduler.
 */
public record DroneStatus(
        int droneId,
        String state,
        Integer currentZoneId
) { }

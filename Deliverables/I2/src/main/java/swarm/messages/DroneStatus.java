package swarm.messages;

/**
 * Produced by DroneSubsystem, consumed by Scheduler and FireIncidentSubsystem.
 */
public record DroneStatus(
        int droneId,
        DroneState state,
        Integer currentZoneId,
        int remainingAgentLiters
) { }

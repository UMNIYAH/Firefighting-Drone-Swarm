package swarm.messages;

/**
 * Drone lifecycle states.
 */
public enum DroneState {
    IDLE,
    EN_ROUTE,
    ARRIVED,
    DROPPING_AGENT,
    RETURNING,
    REFILLING,
    SOFT_FAULT,
    HARD_FAULT
}

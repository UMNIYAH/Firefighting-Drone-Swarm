package swarm.messages;

/**
 * Drone lifecycle states for Iteration 2.
 */
public enum DroneState {
    IDLE,
    EN_ROUTE,
    ARRIVED,
    DROPPING_AGENT,
    RETURNING,
    REFILLING,
    FAULT
}

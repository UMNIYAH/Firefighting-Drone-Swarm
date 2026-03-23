package swarm.messages;

/**
 * Types of faults that can be injected into a drone during simulation.
 *
 * NONE           no fault (default)
 * DRONE_STUCK    drone freezes mid-flight (soft fault, recoverable after reset)
 * NOZZLE_JAMMED  nozzle/bay doors stuck (hard fault, drone permanently offline)
 * PACKET_LOSS    status messages dropped/corrupted (soft fault)
 */
public enum FaultType {
    NONE(false),
    DRONE_STUCK(false),
    NOZZLE_JAMMED(true),
    PACKET_LOSS(false);

    private final boolean hardFault;

    FaultType(boolean hardFault) {
        this.hardFault = hardFault;
    }

    /**
     * Hard faults permanently disable the drone.
     * Soft faults are recoverable after a timeout/reset.
     */
    public boolean isHardFault() {
        return hardFault;
    }
}
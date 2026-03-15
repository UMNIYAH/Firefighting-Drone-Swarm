package swarm.messages;

public enum Severity {
    LOW(10),
    MODERATE(20),
    HIGH(30);

    private final int litersRequired;

    Severity(int litersRequired) {
        this.litersRequired = litersRequired;
    }

    public int litersRequired() {
        return litersRequired;
    }
}
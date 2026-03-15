package swarm.model;

/**
 * Simple 2D position in meters.
 */
public record Position(double x, double y) {

    public double distanceTo(Position other) {
        double dx = other.x - this.x;
        double dy = other.y - this.y;
        return Math.sqrt(dx * dx + dy * dy);
    }
}

package swarm.model;

/**
 * Rectangular fire zone with start/end coordinates.
 */
public record Zone(int id, Position start, Position end) {

    public Position center() {
        double cx = (start.x() + end.x()) / 2.0;
        double cy = (start.y() + end.y()) / 2.0;
        return new Position(cx, cy);
    }
}

package swarm.infra;

import swarm.model.Position;

/**
 * Central configuration for drone parameters based on Iteration 0 data.
 */
public final class DroneConfig {

    private DroneConfig() {}

    private static boolean TEST_MODE = false;
    public static void enableTestMode() {
        TEST_MODE = true;
    }
    public static void disableTestMode() {
        TEST_MODE = false;
    }

    // From Iteration 0:
    // Cruise speed: 8 m/s (conservative)
    // Takeoff acceleration: 1.5 m/s^2
    // Landing deceleration: 1.0 m/s^2
    // Takeoff time: 5.3 s, distance: 21.2 m
    // Landing time: 8.0 s, distance: 32.0 m
    // Nozzle door open/close: 0.3 s each
    // Drop rate: 7.2 L/min = 0.12 L/s

    public static final double CRUISE_SPEED_MPS = 8.0;
    public static final double TAKEOFF_ACCEL_MPS2 = 1.5;
    public static final double LANDING_DECEL_MPS2 = 1.0;

    public static final double TAKEOFF_TIME_S = 5.3;
    public static final double TAKEOFF_DIST_M = 21.2;
    public static final double LANDING_TIME_S = 8.0;
    public static final double LANDING_DIST_M = 32.0;

    public static final double DOOR_OPEN_TIME_S = 0.3;
    public static final double DOOR_CLOSE_TIME_S = 0.3;

    public static final double DROP_RATE_LPS = 7.2 / 60.0; // 7.2 L/min

    public static final int AGENT_CAPACITY_LITERS = 30; // max load
    public static final Position BASE_POSITION = new Position(0.0, 0.0);

    /**
     * Travel time using accel–cruise–decel profile.
     */
    public static long travelTimeMillis(double distanceMeters) {

        if(TEST_MODE) return 500;

        double accelDecelDist = TAKEOFF_DIST_M + LANDING_DIST_M;
        double totalSeconds;

        if (distanceMeters <= accelDecelDist) {
            // Too short for full accel+cruise+decel; approximate with constant cruise
            totalSeconds = distanceMeters / CRUISE_SPEED_MPS;
        } else {
            double cruiseDist = distanceMeters - accelDecelDist;
            double cruiseTime = cruiseDist / CRUISE_SPEED_MPS;
            totalSeconds = TAKEOFF_TIME_S + LANDING_TIME_S + cruiseTime;
        }
        return (long) (totalSeconds * 1000);
    }

    /**
     * Drop time based on required liters and 7.2 L/min rate.
     */
    public static long dropTimeMillis(int litersRequired) {

        if(TEST_MODE) return 500;

        double seconds = litersRequired / DROP_RATE_LPS;
        return (long) (seconds * 1000);
    }

    public static long doorOpenCloseMillis()
    {
        if(TEST_MODE) return 200;

        return (long) ((DOOR_OPEN_TIME_S + DOOR_CLOSE_TIME_S) * 1000);
    }
}

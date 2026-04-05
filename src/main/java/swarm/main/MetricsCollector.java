package swarm.main;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collects and computes performance metrics for the drone swarm simulation.
 *
 * Tracked metrics (per Iteration 5 requirements):
 * - Response time: fire detected → drone arrives at zone
 * - Fire extinguish time: fire detected → fire fully extinguished (DROPPING_AGENT complete)
 * - Average drone idle time: total time each drone spends IDLE between missions
 * - Drone flight time: total time each drone spends flying (EN_ROUTE + RETURNING)
 * - Incident-to-all-clear time: first fire event → last fire serviced
 * - Per-drone mission counts
 */
public class MetricsCollector {

    public static MetricsCollector instance;

    /**
     * Stores timestamps for each mission keyed by "zoneId:severity" at time of fire event.
     * */
    private static class MissionRecord {
        final int zoneId;
        final String severity;
        final long fireDetectedMs; // when FIRE message arrived at Scheduler
        long droneDispatchedMs; // when tryDispatch sent CMD
        long droneArrivedMs; // when ARRIVED status received
        long fireExtinguishedMs; // when DROPPING_AGENT status received (agent applied)
        int assignedDroneId = -1;

        MissionRecord(int zoneId, String severity, long fireDetectedMs) {
            this.zoneId = zoneId;
            this.severity = severity;
            this.fireDetectedMs = fireDetectedMs;
        }
    }

    // Key = missionId (auto-incremented), Value = record
    private final Map<Integer, MissionRecord> missionRecords = new ConcurrentHashMap<>();
    private int nextMissionId = 1;

    // Reverse lookup: droneId -> current missionId (so STATUS updates can find the record)
    private final Map<Integer, Integer> droneToMission = new ConcurrentHashMap<>();

    private static class DroneTimeRecord {
        long totalIdleMs;
        long totalFlightMs; // EN_ROUTE + RETURNING
        long lastStateChangeMs; // timestamp of last state transition
        String lastState = "IDLE";
        int missionsCompleted;
    }

    private final Map<Integer, DroneTimeRecord> droneRecords = new ConcurrentHashMap<>();

    private long firstFireDetectedMs = -1;
    private long lastFireExtinguishedMs = -1;

    /**
     * Called when the Scheduler receives a FIRE message. Returns a missionId.
     * */
    public synchronized int recordFireDetected(int zoneId, String severity) {
        long now = System.currentTimeMillis();
        if (firstFireDetectedMs < 0) {
            firstFireDetectedMs = now;
        }
        int missionId = nextMissionId++;
        missionRecords.put(missionId, new MissionRecord(zoneId, severity, now));
        return missionId;
    }

    /**
     * Called when the Scheduler dispatches a drone to a mission.
     * */
    public void recordDroneDispatched(int missionId, int droneId) {
        MissionRecord rec = missionRecords.get(missionId);
        if (rec != null) {
            rec.droneDispatchedMs = System.currentTimeMillis();
            rec.assignedDroneId = droneId;
        }
        droneToMission.put(droneId, missionId);
    }

    /**
     * Called when a drone reports ARRIVED.
     * */
    public void recordDroneArrived(int droneId) {
        Integer missionId = droneToMission.get(droneId);
        if (missionId != null) {
            MissionRecord rec = missionRecords.get(missionId);
            if (rec != null) {
                rec.droneArrivedMs = System.currentTimeMillis();
            }
        }
    }

    /** C
     * alled when a drone reports DROPPING_AGENT (fire is being extinguished).
     * */
    public void recordFireExtinguished(int droneId) {
        long now = System.currentTimeMillis();
        lastFireExtinguishedMs = now;

        Integer missionId = droneToMission.get(droneId);
        if (missionId != null) {
            MissionRecord rec = missionRecords.get(missionId);
            if (rec != null) {
                rec.fireExtinguishedMs = now;
            }
        }
    }

    /**
     * Called when a drone reports IDLE (mission complete — clears drone→mission mapping).
     * */
    public void recordMissionComplete(int droneId) {
        droneToMission.remove(droneId);
        DroneTimeRecord dr = droneRecords.get(droneId);
        if (dr != null) {
            dr.missionsCompleted++;
        }
    }

    /**
     * Called on every drone state change, so we can accumulate idle/flight time.
     * Must be called BEFORE the state is overwritten in Scheduler.droneStates.
     */
    public void recordDroneStateChange(int droneId, String previousState, String newState) {
        long now = System.currentTimeMillis();
        DroneTimeRecord dr = droneRecords.computeIfAbsent(droneId, id -> {
            DroneTimeRecord r = new DroneTimeRecord();
            r.lastStateChangeMs = now;
            return r;
        });

        long elapsed = now - dr.lastStateChangeMs;

        // Accumulate time in the PREVIOUS state
        switch (previousState) {
            case "IDLE" -> dr.totalIdleMs += elapsed;
            case "EN_ROUTE", "RETURNING" -> dr.totalFlightMs += elapsed;
            // Other states (ARRIVED, DROPPING_AGENT, REFILLING, faults) aren't
            // counted toward idle or flight, but you could track them similarly.
        }

        dr.lastState = newState;
        dr.lastStateChangeMs = now;
    }

    /**
     * Register a drone so its time tracking starts from simulation begin.
     * */
    public void registerDrone(int droneId) {
        droneRecords.computeIfAbsent(droneId, id -> {
            DroneTimeRecord r = new DroneTimeRecord();
            r.lastStateChangeMs = System.currentTimeMillis();
            return r;
        });
    }

    /**
     * Average response time (fire detected → drone arrived) across all completed missions.
     * */
    public double avgResponseTimeMs() {
        return missionRecords.values().stream()
                .filter(r -> r.droneArrivedMs > 0)
                .mapToLong(r -> r.droneArrivedMs - r.fireDetectedMs)
                .average().orElse(0);
    }

    /**
     * Average extinguish time (fire detected → agent dropped) across all completed missions.
     * */
    public double avgExtinguishTimeMs() {
        return missionRecords.values().stream()
                .filter(r -> r.fireExtinguishedMs > 0)
                .mapToLong(r -> r.fireExtinguishedMs - r.fireDetectedMs)
                .average().orElse(0);
    }

    /**
     * Total time from first fire detected to last fire extinguished.
     * */
    public long totalIncidentToAllClearMs() {
        if (firstFireDetectedMs < 0 || lastFireExtinguishedMs < 0) return 0;
        return lastFireExtinguishedMs - firstFireDetectedMs;
    }

    /**
     * Average idle time across all drones.
     * */
    public double avgDroneIdleTimeMs() {
        flushCurrentStates(); // count time in current state up to now
        return droneRecords.values().stream()
                .mapToLong(r -> r.totalIdleMs)
                .average().orElse(0);
    }

    /**
     * Total flight time for a specific drone.
     * */
    public long droneFlightTimeMs(int droneId) {
        flushCurrentStates();
        DroneTimeRecord dr = droneRecords.get(droneId);
        return dr != null ? dr.totalFlightMs : 0;
    }

    /**
     * Average flight time across all drones.
     * */
    public double avgDroneFlightTimeMs() {
        flushCurrentStates();
        return droneRecords.values().stream()
                .mapToLong(r -> r.totalFlightMs)
                .average().orElse(0);
    }

    /**
     * Missions completed by a specific drone.
     * */
    public int droneMissionsCompleted(int droneId) {
        DroneTimeRecord dr = droneRecords.get(droneId);
        return dr != null ? dr.missionsCompleted : 0;
    }

    /**
     * Flush: count time in the current state up to "now" without changing state.
     * This makes idle/flight averages accurate at any point during the simulation.
     */
    private void flushCurrentStates() {
        long now = System.currentTimeMillis();
        for (DroneTimeRecord dr : droneRecords.values()) {
            long elapsed = now - dr.lastStateChangeMs;
            switch (dr.lastState) {
                case "IDLE" -> dr.totalIdleMs += elapsed;
                case "EN_ROUTE", "RETURNING" -> dr.totalFlightMs += elapsed;
            }
            dr.lastStateChangeMs = now;
        }
    }

    /**
     * Builds a human-readable summary of all metrics.
     * */
    public String buildSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("═ PERFORMANCE METRICS ═\n");
        sb.append(String.format("  Avg response time (detect→arrive):  %.1f s%n", avgResponseTimeMs() / 1000.0));
        sb.append(String.format("  Avg extinguish time (detect→drop):  %.1f s%n", avgExtinguishTimeMs() / 1000.0));
        sb.append(String.format("  Total incident→all-clear time:      %.1f s%n", totalIncidentToAllClearMs() / 1000.0));
        sb.append(String.format("  Avg drone idle time:                %.1f s%n", avgDroneIdleTimeMs() / 1000.0));
        sb.append(String.format("  Avg drone flight time:              %.1f s%n", avgDroneFlightTimeMs() / 1000.0));
        sb.append("──────────────────────────────────────\n");

        for (Map.Entry<Integer, DroneTimeRecord> e : new TreeMap<>(droneRecords).entrySet()) {
            int id = e.getKey();
            DroneTimeRecord dr = e.getValue();
            sb.append(String.format("  Drone %d:  missions=%d  flight=%.1fs  idle=%.1fs%n",
                    id, dr.missionsCompleted, dr.totalFlightMs / 1000.0, dr.totalIdleMs / 1000.0));
        }
        sb.append("══════════════════════════════════════\n");
        return sb.toString();
    }

    /**
     * Prints the summary to System: out and to the GUI log.
     * */
    public void printSummary() {
        String summary = buildSummary();
        System.out.println(summary);
        if (SimulatorGUI.instance != null) {
            for (String line : summary.split("\n")) {
                SimulatorGUI.instance.log(line);
            }
        }
    }
}
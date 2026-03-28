package swarm.subsystems;

import swarm.infra.DroneConfig;
import swarm.infra.UDPHelper;
import swarm.infra.ZoneManager;
import swarm.main.SimulatorGUI;
import swarm.messages.DroneState;
import swarm.messages.FaultType;
import swarm.messages.Severity;
import swarm.model.Position;

/**
 * Drone subsystem – Iteration 4.
 *
 * Fault behaviours (injected via the CMD's fault field):
 *
 *   DRONE_STUCK   – drone freezes mid-flight (soft fault).
 *                   Reports SOFT_FAULT, sleeps for STUCK_RESET_MS, then
 *                   sends IDLE so the Scheduler can reassign the mission.
 *                   The watchdog on the Scheduler side also fires independently;
 *                   whichever resolves first wins.
 *
 *   NOZZLE_JAMMED – hard fault during the DROPPING_AGENT phase.
 *                   Reports HARD_FAULT and permanently stops processing
 *                   new missions (exits the mission loop).
 *
 *   PACKET_LOSS   – two consecutive STATUS messages are silently dropped
 *                   just before DROPPING_AGENT, simulating network loss.
 *                   The drone eventually completes the mission normally.
 */
public class DroneSubsystem implements Runnable {

    /** How long (ms, simulation-scaled) a stuck drone is frozen before self-recovery. */
    private static final long STUCK_RESET_MS = 3_000;

    /** Number of consecutive STATUS messages dropped for PACKET_LOSS. */
    private static final int PACKET_LOSS_DROP_COUNT = 2;

    private final UDPHelper udp;
    private final int droneId;
    private final int port;
    private final ZoneManager zoneManager;
    private int currentAgent;
    private Position currentPosition;

    /** When true the drone has suffered a hard fault and will not accept more missions. */
    private volatile boolean hardFaulted = false;

    public DroneSubsystem(UDPHelper udp, int droneId, int port, ZoneManager zoneManager) {
        this.udp           = udp;
        this.droneId       = droneId;
        this.port          = port;
        this.zoneManager   = zoneManager;
        this.currentAgent  = DroneConfig.AGENT_CAPACITY_LITERS;
        this.currentPosition = DroneConfig.BASE_POSITION;
    }

    // =========================================================================
    // Runnable entry point
    // =========================================================================

    @Override
    public void run() {
        System.out.println("[Drone " + droneId + "] Listening on port " + port + "...");
        new Thread(this::processMissions, "Drone-" + droneId + "-Processor").start();
    }

    // =========================================================================
    // Mission processing loop
    // =========================================================================

    private void processMissions() {
        while (!hardFaulted) {
            try {
                String message = udp.receive();
                String[] parts = message.split(":");

                if (!parts[0].equals("CMD")) continue;

                // CMD:zoneId:severity:faultType
                int      zoneId   = Integer.parseInt(parts[1]);
                Severity severity  = Severity.valueOf(parts[2]);
                FaultType fault   = (parts.length >= 4)
                        ? FaultType.valueOf(parts[3])
                        : FaultType.NONE;

                Position target = zoneManager.getZoneCenter(zoneId);
                if (target == null) {
                    System.err.println("[Drone " + droneId + "] Zone " + zoneId + " not found, skipping.");
                    continue;
                }

                executeMission(zoneId, severity, target, fault);

            } catch (Exception e) {
                if (!hardFaulted) {
                    System.err.println("[Drone " + droneId + "] Error in mission loop: " + e.getMessage());
                }
            }
        }
        System.out.println("[Drone " + droneId + "] Mission loop terminated (hard fault).");
    }

    // =========================================================================
    // Single mission execution with fault injection
    // =========================================================================

    private void executeMission(int zoneId, Severity severity, Position target, FaultType fault)
            throws InterruptedException {

        // ── EN_ROUTE ──────────────────────────────────────────────────────────
        reportStatus(DroneState.EN_ROUTE, zoneId, FaultType.NONE);
        long flightTime = DroneConfig.travelTimeMillis(currentPosition.distanceTo(target));

        // DRONE_STUCK: freeze mid-flight, report soft fault, then self-recover
        if (fault == FaultType.DRONE_STUCK) {
            Thread.sleep(flightTime / 20);     // fly partway
            reportStatus(DroneState.SOFT_FAULT, zoneId, FaultType.DRONE_STUCK);
            System.out.println("[Drone " + droneId + "] STUCK mid-flight on zone " + zoneId
                    + " — resetting in " + STUCK_RESET_MS + " ms");
            Thread.sleep(STUCK_RESET_MS);

            // Self-recovery: return to idle so Scheduler can reassign
            currentPosition = DroneConfig.BASE_POSITION;
            currentAgent    = DroneConfig.AGENT_CAPACITY_LITERS;
            reportStatus(DroneState.IDLE, null, FaultType.NONE);
            return;   // mission aborted; Scheduler will requeue
        }

        Thread.sleep(flightTime / 10);
        currentPosition = target;

        // ── ARRIVED ───────────────────────────────────────────────────────────
        reportStatus(DroneState.ARRIVED, zoneId, FaultType.NONE);

        // PACKET_LOSS: drop the next two outbound STATUS messages silently
        int packetsToSkip = (fault == FaultType.PACKET_LOSS) ? PACKET_LOSS_DROP_COUNT : 0;

        // ── DROPPING_AGENT ────────────────────────────────────────────────────
        // NOZZLE_JAMMED: hard fault — bay doors cannot open
        if (fault == FaultType.NOZZLE_JAMMED) {
            hardFaulted = true;
            reportStatus(DroneState.HARD_FAULT, zoneId, FaultType.NOZZLE_JAMMED);
            System.out.println("[Drone " + droneId + "] NOZZLE JAMMED on zone " + zoneId
                    + " — drone permanently offline");
            return;   // exits processMissions loop via hardFaulted flag
        }

        reportStatusMaybeDropped(DroneState.DROPPING_AGENT, zoneId, FaultType.NONE, packetsToSkip > 0);
        if (packetsToSkip > 0) packetsToSkip--;

        long dropTime = DroneConfig.dropTimeMillis(severity.litersRequired())
                + DroneConfig.doorOpenCloseMillis();
        Thread.sleep(dropTime / 10);
        currentAgent -= severity.litersRequired();
        if (currentAgent < 0) currentAgent = 0;

        // ── RETURNING ─────────────────────────────────────────────────────────
        reportStatusMaybeDropped(DroneState.RETURNING, zoneId, FaultType.NONE, packetsToSkip > 0);
        if (packetsToSkip > 0) packetsToSkip--;

        long returnTime = DroneConfig.travelTimeMillis(
                currentPosition.distanceTo(DroneConfig.BASE_POSITION));
        Thread.sleep(returnTime / 10);

        // ── REFILLING ─────────────────────────────────────────────────────────
        currentPosition = DroneConfig.BASE_POSITION;
        currentAgent    = DroneConfig.AGENT_CAPACITY_LITERS;
        reportStatus(DroneState.REFILLING, zoneId, FaultType.NONE);
        Thread.sleep(200);

        // ── IDLE ──────────────────────────────────────────────────────────────
        reportStatus(DroneState.IDLE, null, FaultType.NONE);
    }

    // =========================================================================
    // Status reporting helpers
    // =========================================================================

    /**
     * Sends a STATUS message to the Scheduler.
     * Format: STATUS:droneId:state:zoneId:posX:posY:faultType
     */
    private void reportStatus(DroneState state, Integer zoneId, FaultType faultType) {
        try {
            String zoneIdStr = (zoneId != null) ? String.valueOf(zoneId) : "0";
            String msg = "STATUS:" + droneId
                    + ":" + state.name()
                    + ":" + zoneIdStr
                    + ":" + currentPosition.x()
                    + ":" + currentPosition.y()
                    + ":" + faultType.name();
            udp.send(msg, 5000);

            if (SimulatorGUI.instance != null) {
                SimulatorGUI.instance.log("[Drone " + droneId + "] " + state
                        + (zoneId != null ? " (Zone " + zoneId + ")" : "")
                        + (faultType != FaultType.NONE ? " [" + faultType + "]" : ""));
            }
        } catch (Exception e) {
            System.err.println("[Drone " + droneId + "] Failed to send status: " + e.getMessage());
        }
    }

    /**
     * Like reportStatus but optionally silently drops the message (PACKET_LOSS simulation).
     */
    private void reportStatusMaybeDropped(DroneState state, Integer zoneId,
                                          FaultType faultType, boolean drop) {
        if (drop) {
            System.out.println("[Drone " + droneId + "] [PACKET_LOSS] dropping STATUS:" + state);
            // Log locally but do NOT send UDP
            if (SimulatorGUI.instance != null) {
                SimulatorGUI.instance.log("[Drone " + droneId + "] [PACKET_LOSS] dropped STATUS:"
                        + state + " (Zone " + zoneId + ")");
            }
        } else {
            reportStatus(state, zoneId, faultType);
        }
    }

    // =========================================================================
    // Standalone entry point
    // =========================================================================

    public static void main(String[] args) {
        try {
            int droneId = args.length > 0 ? Integer.parseInt(args[0]) : 1;
            int port    = 6000 + droneId;

            ZoneManager zm  = new ZoneManager("sample_zone_file.csv");
            UDPHelper   udp = new UDPHelper(port);
            new DroneSubsystem(udp, droneId, port, zm).run();
        } catch (Exception e) {
            System.err.println("Failed to start Drone " + (args.length > 0 ? args[0] : "1"));
            e.printStackTrace();
        }
    }
}
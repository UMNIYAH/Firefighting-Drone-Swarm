package swarm.subsystems;

import swarm.infra.UDPHelper;
import swarm.main.SimulatorGUI;
import swarm.messages.DroneCommand;
import swarm.messages.DroneState;
import swarm.messages.DroneStatus;
import swarm.messages.FireEvent;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduler subsystem.
 *
 * Responsibilities (Iteration 1):
 * 1. Receive fire events from the FireIncidentSubsystem
 * 2. Decide which drone to dispatch (single-drone assumption)
 * 3. Send commands to the DroneSubsystem
 * 4. Receive and log drone status updates
 *
 * Scheduling logic is intentionally minimal for Iteration 1.
 */
public class Scheduler implements Runnable {

    private final UDPHelper udp;

    public Scheduler(UDPHelper udp) {
        this.udp = udp;
    }

    @Override
    public void run() {
        System.out.println("[Scheduler] Listening on port 5000...");

        try{
            // listening for incoming messages
            while(true){
                String message = udp.receive();
                handleMessage(message);
            }
        } catch (Exception e){
            System.err.println("[Scheduler] Network error: " + e.getMessage());
        }
    }

    /**
     * Consumes FireEvent messages and dispatches a drone.
     */
    private void handleMessage(String message) {
        // Split string
        String[] parts = message.split(":");
        String command = parts[0];

        // Handle new fire event
        if (command.equals("FIRE")){
            int zoneId = Integer.parseInt(parts[1]);
            String severity =  parts[2];
            String type = parts[3];

            System.out.println("[Scheduler] Received fire event: Zone " + zoneId);

            try {
                String cmdMessage = "CMD:" + zoneId + ":" + severity;
                udp.send(cmdMessage, 6000);
                System.out.println("[Scheduler] Dispatched drone to Zone " + zoneId);
            } catch (Exception e){
                System.err.println("[Scheduler] Failed to dispatch drone.");
            }
        }

        // Handle status update from Drone
        else if (command.equals("STATUS")){
            int droneId = Integer.parseInt(parts[1]);
            DroneState state = DroneState.valueOf(parts[2]);
            int zoneId = Integer.parseInt(parts[3]);

            System.out.println("[Drone " + droneId + "] is now "  + state + " (Zone " + zoneId + ")");

            // Update GUI
            if (SimulatorGUI.instance != null){
                SimulatorGUI.instance.updateDroneState(state);

                // if drone is done, lower active fire count
                if (state == DroneState.IDLE){
                    SimulatorGUI.instance.decrementFire();
                }
            }
        } else {
            System.out.println("[Scheduler] Unknown command: " + command);
        }
    }
}
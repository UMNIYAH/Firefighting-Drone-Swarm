package swarm.main;

import swarm.infra.UDPHelper;
import swarm.infra.ZoneManager;
import swarm.subsystems.DroneSubsystem;
import swarm.subsystems.FireIncidentSubsystem;
import swarm.subsystems.Scheduler;

import javax.swing.*;

/**
 * One-click launcher that starts all subsystems in the correct order.
 * Ensures each subsystem's UDP port is bound before the next one starts.
 */
public class Launcher {

    public static void main(String[] args) {
        int numDrones = args.length > 0 ? Integer.parseInt(args[0]) : 1;

        // 1. Start GUI (on the Swing thread)
        SwingUtilities.invokeLater(SimulatorGUI::new);

        // 2. Start subsystems on a background thread (so we don't block Swing)
        new Thread(() -> {
            try {
                // Small delay to let GUI initialize
                Thread.sleep(500);
                
                ZoneManager zm = new ZoneManager("sample_zone_file.csv");
                UDPHelper schedulerUdp = new UDPHelper(5000);
                new Thread(new Scheduler(schedulerUdp, numDrones, zm), "Scheduler").start();
                System.out.println("[Launcher] Scheduler started on port 5000");

                for (int i = 1; i <= numDrones; i++) {
                    int port = 6000 + i;
                    ZoneManager droneZm = new ZoneManager("sample_zone_file.csv");
                    UDPHelper droneUdp = new UDPHelper(port);
                    new Thread(new DroneSubsystem(droneUdp, i, port, droneZm), "Drone-" + i).start();
                    System.out.println("[Launcher] Drone " + i + " started on port " + port);
                }

                // Small delay to ensure Scheduler & Drones are listening
                Thread.sleep(500);

                // 5. Start Fire Incident Subsystem (sends events — must be last)
                UDPHelper fireUdp = new UDPHelper();
                new Thread(new FireIncidentSubsystem(fireUdp, "Sample_event_file.csv"), "FireIncident").start();
                System.out.println("[Launcher] FireIncidentSubsystem started");

            } catch (Exception e) {
                System.err.println("[Launcher] Failed to start subsystems");
                e.printStackTrace();
            }
        }, "Launcher-Init").start();
    }
}
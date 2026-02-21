package swarm.main;

import swarm.infra.MessageBus;
import swarm.infra.ZoneManager;
import swarm.subsystems.FireIncidentSubsystem;
import swarm.subsystems.Scheduler;
import swarm.subsystems.DroneSubsystem;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;

public class SimulatorGUI {

    private final JTextArea logArea = new JTextArea();
    private final MessageBus bus = new MessageBus(50);
    private final ZoneManager zoneManager;

    public SimulatorGUI() {
        // Initialize ZoneManager safely
        ZoneManager tempZoneManager = null;
        try {
            tempZoneManager = new ZoneManager("sample_zone_file.csv");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null,
                    "Failed to load zone file: " + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
            System.exit(1); // Stop program if zones can't be loaded
        }
        zoneManager = tempZoneManager;

        JFrame frame = new JFrame("Firefighting Drone Simulator – Iteration 1");
        frame.setSize(700, 500);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        // Log window
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);

        // Start button
        JButton startButton = new JButton("Start Simulation");
        startButton.addActionListener(e -> startSimulation());

        frame.add(scrollPane, BorderLayout.CENTER);
        frame.add(startButton, BorderLayout.SOUTH);

        frame.setVisible(true);
    }

    /**
     * Thread‑safe logging from any subsystem.
     */
    public void log(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    /**
     * Starts all subsystems in background threads.
     */
    private void startSimulation() {
        log("Starting subsystems...");

        // FireIncidentSubsystem
        Thread fireThread = new Thread(
                new FireIncidentSubsystem(bus, "Sample_event_file.csv"),
                "FireIncidentSubsystem"
        );

        // Scheduler
        Thread schedulerThread = new Thread(
                new Scheduler(bus),
                "Scheduler"
        );

        // Drone
        Thread droneThread = new Thread(
                new DroneSubsystem(bus, 1, zoneManager),
                "DroneSubsystem"
        );

        fireThread.start();
        schedulerThread.start();
        droneThread.start();

        log("Simulation running.");
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(SimulatorGUI::new);
    }
}

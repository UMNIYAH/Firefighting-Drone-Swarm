package swarm.main;

import swarm.infra.MessageBus;
import swarm.subsystems.FireIncidentSubsystem;
import swarm.subsystems.Scheduler;
import swarm.subsystems.DroneSubsystem;

import javax.swing.*;
import java.awt.*;

public class SimulatorGUI {

    private final JTextArea logArea = new JTextArea();
    private final MessageBus bus = new MessageBus(50);
    private final ZoneManager zoneManager;

    // I2
    private final JLabel droneStateLabel = new JLabel("Drone State: IDLE");
    private final JLabel incidentCounterLabel = new JLabel("Active Incidents: 0");
    private final AtomicInteger activeIncidents = new AtomicInteger(0);

    public SimulatorGUI() {
        // Load ZoneManager
        ZoneManager tempZoneManager = null;
        try{
            tempZoneManager = new ZoneManager("sample_zone_file.csv");
        } catch (IOException e){
            JOptionPane.showMessageDialog(null, "Failed to load zone file: " + e.getMessage());
            System.exit(1);
        }
        zoneManager = tempZoneManager;

        JFrame frame = new JFrame("Firefighting Drone Simulator – Iteration 1");
        frame.setSize(700, 500);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        // I2 panel
        JPanel statsPanel = new JPanel(new GridLayout(1, 2));
        statsPanel.setBorder(BorderFactory.createTitledBorder("System Monitor"));

        droneStateLabel.setFont(new Font("Arial", Font.BOLD, 20));
        incidentCounterLabel.setFont(new Font("Arial", Font.BOLD, 20));

        statsPanel.add(droneStateLabel);
        statsPanel.add(incidentCounterLabel);
        frame.add(statsPanel, BorderLayout.NORTH);

        // Log window
        logArea.setEditable(false);
        frame.add(new JScrollPane(logArea), BorderLayout.CENTER);

        // Start button
        JButton startButton = new JButton("Start Simulation");
        startButton.addActionListener(e -> {
            startButton.setEnabled(false);
            startSimulation();
        });
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

        // Thread: Track Active Fires
        new Thread(() -> {
            try {
                while (true) {
                    // listens for new fires being detected
                    FireEvent event = bus.fireEvents.take();
                    int count = activeIncidents.incrementAndGet();

                    SwingUtilities.invokeLater(() -> {
                        incidentCounterLabel.setText("Active Incidents: " + count);
                        log("New incident: Zone" + event.zoneId() + " reported.");
                    });
                }
            } catch (InterruptedException e)  { Thread.currentThread().interrupt(); }
        }, "GUI-Fire-Tracker").start();

        // Thread: Track Drone States
        new Thread(() -> {
            try {
                while (true) {
                    // Listen for Drone State changes
                    DroneStatus status = bus.droneStasuses.take();

                    SwingUtilities.invokeLater(() -> {
                        droneStateLabel.setText("Drone State: " + status.state());

                        // If drone is IDLE, assume it finished a fire
                        if (status.state().toString.equals("IDLE") && activeIncidents.get() > 0){
                            int remaining = activeIncidents.decrementAndGet();
                            incidentCounterLabel.setText("Active Incidents: " + remaining);
                        }
                    });
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "GUI-State-Tracker").start();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(SimulatorGUI::new);
    }
}

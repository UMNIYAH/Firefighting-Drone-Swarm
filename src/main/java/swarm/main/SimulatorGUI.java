package swarm.main;

import swarm.infra.UDPHelper;
import swarm.infra.ZoneManager;
import swarm.messages.DroneState;
import swarm.subsystems.FireIncidentSubsystem;
import swarm.subsystems.Scheduler;
import swarm.subsystems.DroneSubsystem;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class SimulatorGUI {

    public static SimulatorGUI instance;

    private final JTextArea logArea = new JTextArea();
    private final ZoneManager zoneManager;

    // Per-drone state display
    private final JPanel dronePanel = new JPanel();
    private final Map<Integer, JLabel> droneLabels = new ConcurrentHashMap<>();

    private final JLabel incidentCounterLabel = new JLabel("Active Incidents: 0");
    private final AtomicInteger activeIncidents = new AtomicInteger(0);

    private final Map<Integer, JLabel> zoneStatusLabels = new HashMap<>();
    private final Map<Integer, JLabel> zoneSeverityLabels = new HashMap<>();
    private final Map<Integer, JLabel> zoneDroneLabels = new HashMap<>();

    public SimulatorGUI() {
        instance = this;

        ZoneManager tempZoneManager = null;
        try {
            tempZoneManager = new ZoneManager("sample_zone_file.csv");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null, "Failed to load zone file: " + e.getMessage());
            System.exit(1);
        }
        zoneManager = tempZoneManager;

        JFrame frame = new JFrame("Firefighting Drone Simulator – Iteration 3");
        frame.setSize(950, 600);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        // Top panel: drone states + incident counter
        JPanel statsPanel = new JPanel(new BorderLayout());
        statsPanel.setBorder(BorderFactory.createTitledBorder("System Monitor"));

        dronePanel.setLayout(new BoxLayout(dronePanel, BoxLayout.Y_AXIS));
        statsPanel.add(dronePanel, BorderLayout.CENTER);

        incidentCounterLabel.setFont(new Font("Arial", Font.BOLD, 18));
        statsPanel.add(incidentCounterLabel, BorderLayout.EAST);

        frame.add(statsPanel, BorderLayout.NORTH);

        // Zone Map (3x3 Grid)
        JPanel gridPanel = new JPanel(new GridLayout(3, 3, 0, 0));
        gridPanel.setBorder(BorderFactory.createTitledBorder("Zone Map"));

        JPanel mapContainer = new JPanel(new BorderLayout());
        mapContainer.setBorder(BorderFactory.createLineBorder(Color.BLACK, 3));

        for (int i = 1; i <= 9; i++) {
            JPanel cell = new JPanel(new BorderLayout());
            cell.setBackground(Color.WHITE);
            cell.setBorder(BorderFactory.createLineBorder(Color.BLACK, 1));

            JLabel nameLabel = new JLabel("Zone " + i, SwingConstants.CENTER);
            nameLabel.setFont(new Font("Arial", Font.PLAIN, 14));
            nameLabel.setForeground(Color.DARK_GRAY);
            cell.add(nameLabel, BorderLayout.NORTH);

            // Fire icon area
            JLabel statusLabel = new JLabel("", SwingConstants.CENTER);
            statusLabel.setFont(new Font("Arial", Font.BOLD, 36));
            cell.add(statusLabel, BorderLayout.CENTER);
            zoneStatusLabels.put(i, statusLabel);

            // Bottom of cell: severity + assigned drone
            JPanel cellInfo = new JPanel(new GridLayout(2, 1));
            JLabel sevLabel = new JLabel("", SwingConstants.CENTER);
            sevLabel.setFont(new Font("Arial", Font.PLAIN, 11));
            sevLabel.setForeground(Color.RED);
            cellInfo.add(sevLabel);
            zoneSeverityLabels.put(i, sevLabel);

            JLabel drLabel = new JLabel("", SwingConstants.CENTER);
            drLabel.setFont(new Font("Arial", Font.ITALIC, 11));
            drLabel.setForeground(Color.BLUE);
            cellInfo.add(drLabel);
            zoneDroneLabels.put(i, drLabel);

            cell.add(cellInfo, BorderLayout.SOUTH);

            gridPanel.add(cell);
        }
        mapContainer.add(gridPanel, BorderLayout.CENTER);

        JPanel mapWrapper = new JPanel(new BorderLayout());
        mapWrapper.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        mapWrapper.add(mapContainer, BorderLayout.CENTER);
        frame.add(mapWrapper, BorderLayout.CENTER);

        // Log window
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setPreferredSize(new Dimension(300, 0));
        frame.add(scrollPane, BorderLayout.EAST);

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
     * Gui update methods
     */
    public void incrementFire() {
        int count = activeIncidents.incrementAndGet();
        SwingUtilities.invokeLater(() -> incidentCounterLabel.setText("Active Incidents: " + count));
    }

    public void decrementFire() {
        if (activeIncidents.get() > 0) {
            int count = activeIncidents.decrementAndGet();
            SwingUtilities.invokeLater(() -> incidentCounterLabel.setText("Active Incidents: " + count));
        }
    }

    /** Update per-drone state display in the top panel */
    public void updateDroneInfo(int droneId, DroneState state, int zoneId) {
        SwingUtilities.invokeLater(() -> {
            JLabel label = droneLabels.get(droneId);
            if (label == null) {
                label = new JLabel();
                label.setFont(new Font("Arial", Font.BOLD, 14));
                droneLabels.put(droneId, label);
                dronePanel.add(label);
                dronePanel.revalidate();
            }
            String zoneText = (zoneId != 0) ? " → Zone " + zoneId : "";
            label.setText("Drone " + droneId + ": " + state + zoneText);
        });
    }

    /** Show fire icon + severity on the zone map */
    public void setZoneOnFire(int zoneId, String severity) {
        SwingUtilities.invokeLater(() -> {
            JLabel label = zoneStatusLabels.get(zoneId);
            if (label != null) {
                ImageIcon fireIcon = new ImageIcon("fire.png");
                label.setIcon(fireIcon);
                label.setText("");
            }
            JLabel sevLabel = zoneSeverityLabels.get(zoneId);
            if (sevLabel != null) {
                sevLabel.setText(severity);
            }
        });
    }

    /** Show which drone is assigned to a zone */
    public void setZoneDrone(int zoneId, int droneId) {
        SwingUtilities.invokeLater(() -> {
            JLabel drLabel = zoneDroneLabels.get(zoneId);
            if (drLabel != null) {
                drLabel.setText("Drone " + droneId);
            }
        });
    }

    public void clearZone(int zoneId) {
        SwingUtilities.invokeLater(() -> {
            JLabel label = zoneStatusLabels.get(zoneId);
            if (label != null) {
                label.setIcon(null);
                label.setText("");
            }
            JLabel sevLabel = zoneSeverityLabels.get(zoneId);
            if (sevLabel != null) sevLabel.setText("");
            JLabel drLabel = zoneDroneLabels.get(zoneId);
            if (drLabel != null) drLabel.setText("");
        });
    }

    /**
     * Starts all subsystems in background threads.
     */
    private void startSimulation() {
        log("GUI started. Waiting for subsystem updates...");
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(SimulatorGUI::new);
    }
}
package swarm.main;

import swarm.infra.UDPHelper;
import swarm.infra.ZoneManager;
import swarm.messages.DroneState;
import swarm.messages.DroneStatus;
import swarm.messages.FireEvent;
import swarm.subsystems.FireIncidentSubsystem;
import swarm.subsystems.Scheduler;
import swarm.subsystems.DroneSubsystem;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class SimulatorGUI {

    public static SimulatorGUI instance;

    private final JTextArea logArea = new JTextArea();
    private final ZoneManager zoneManager;

    private final JLabel droneStateLabel = new JLabel("Drone State: IDLE");
    private final JLabel incidentCounterLabel = new JLabel("Active Incidents: 0");
    private final AtomicInteger activeIncidents = new AtomicInteger(0);

    private final Map<Integer, JLabel> zoneStatusLabels = new HashMap<>();

    public SimulatorGUI() {
        instance = this;

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
        frame.setSize(850, 500);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        // panel
        JPanel statsPanel = new JPanel(new GridLayout(1, 2));
        statsPanel.setBorder(BorderFactory.createTitledBorder("System Monitor"));

        droneStateLabel.setFont(new Font("Arial", Font.BOLD, 20));
        incidentCounterLabel.setFont(new Font("Arial", Font.BOLD, 20));

        statsPanel.add(droneStateLabel);
        statsPanel.add(incidentCounterLabel);
        frame.add(statsPanel, BorderLayout.NORTH);

        // Zone Map (3x3 Grid)
        JPanel gridPanel = new JPanel(new GridLayout(3, 3, 0, 0));
        gridPanel.setBorder(BorderFactory.createTitledBorder("Zone Map"));

        // Map Border
        JPanel mapContainer = new JPanel(new BorderLayout());
        mapContainer.setBorder(BorderFactory.createLineBorder(Color.BLACK, 3));

        for (int i = 1; i <= 9; i++){
            JPanel cell = new JPanel(new BorderLayout());
            cell.setBackground(Color.WHITE);
            cell.setBorder(BorderFactory.createLineBorder(Color.BLACK, 1));

            // Zone
            JLabel nameLabel = new JLabel("Zone " + i, SwingConstants.CENTER);
            nameLabel.setFont(new Font("Arial", Font.PLAIN, 14));
            nameLabel.setForeground(Color.DARK_GRAY);
            cell.add(nameLabel, BorderLayout.NORTH);

            // Fire zone
            JLabel statusLabel = new JLabel("", SwingConstants.CENTER);
            statusLabel.setFont(new Font("Arial", Font.BOLD, 36));
            cell.add(statusLabel, BorderLayout.CENTER);

            gridPanel.add(cell);
            zoneStatusLabels.put(i, statusLabel);
        }
        mapContainer.add(gridPanel, BorderLayout.CENTER);

        // Padding outside of map
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

    public void updateDroneState(int droneId, DroneState state) {
        SwingUtilities.invokeLater(() -> droneStateLabel.setText("Drone " + droneId + ": " + state));
    }

    public void setZoneOnFire(int zoneId){
        SwingUtilities.invokeLater(() -> {
            JLabel label = zoneStatusLabels.get(zoneId);
            if(label != null){
                ImageIcon fireIcon = new ImageIcon("fire.png");
                label.setIcon(fireIcon);
                label.setText("");
            }
        });
    }

    public void clearZone(int zoneId){
        SwingUtilities.invokeLater(() -> {
            JLabel label = zoneStatusLabels.get(zoneId);
            if(label != null){
                label.setIcon(null);
                label.setText("");
            }
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

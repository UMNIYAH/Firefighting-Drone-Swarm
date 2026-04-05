package swarm.main;

import swarm.infra.ZoneManager;
import swarm.messages.DroneState;
import swarm.messages.FaultType;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class SimulatorGUI {

    public static SimulatorGUI instance;

    // How often the map repaints AND how often drone positions step forward.
    private static final int TICK_MS = 100;

    private static final Map<DroneState, Color> STATE_COLORS = new HashMap<>();
    static {
        STATE_COLORS.put(DroneState.IDLE,           new Color(160, 160, 160));
        STATE_COLORS.put(DroneState.EN_ROUTE,        new Color(70,  130, 220));
        STATE_COLORS.put(DroneState.ARRIVED,         new Color(0,   200, 200));
        STATE_COLORS.put(DroneState.DROPPING_AGENT,  new Color(255, 165,   0));
        STATE_COLORS.put(DroneState.RETURNING,       new Color(150,  70, 200));
        STATE_COLORS.put(DroneState.REFILLING,       new Color(200, 185,   0));
        STATE_COLORS.put(DroneState.SOFT_FAULT,      new Color(230,  80,  30));
        STATE_COLORS.put(DroneState.HARD_FAULT,      new Color(220,  30,  30));
    }

    private final JTextArea     logArea       = new JTextArea();
    private final JPanel        dronePanel    = new JPanel();
    private final JLabel        incidentLabel = new JLabel("Active Incidents: 0");
    private final AtomicInteger activeIncidents = new AtomicInteger(0);
    private MapCanvas mapCanvas;

    private final ZoneManager zoneManager;
    private final Map<Integer, String>           zoneFireSeverity  = new ConcurrentHashMap<>();
    private final Map<Integer, DroneMarker>      droneMarkers      = new ConcurrentHashMap<>();
    private final Map<Integer, DroneRenderState> droneRenderStates = new ConcurrentHashMap<>();

    // =========================================================================
    // DroneRenderState – interpolated movement
    // =========================================================================

    private static class DroneRenderState {
        volatile double renderX, renderY;   // current drawn position
        volatile double targetX, targetY;   // destination
        volatile double stepX,   stepY;     // per-tick delta
        volatile DroneState state = DroneState.IDLE;
        volatile FaultType  fault = FaultType.NONE;

        DroneRenderState(double x, double y) {
            renderX = targetX = x;
            renderY = targetY = y;
        }

        // Advances renderX/Y one step toward target; called every TICK_MS by the Swing timer
        void tick() {
            if (stepX == 0 && stepY == 0) return;
            double remX = targetX - renderX;
            double remY = targetY - renderY;
            // Snap when the remaining distance is smaller than one step
            if (Math.abs(remX) <= Math.abs(stepX) && Math.abs(remY) <= Math.abs(stepY)) {
                renderX = targetX;
                renderY = targetY;
                stepX = stepY = 0;
            } else {
                renderX += stepX;
                renderY += stepY;
            }
        }

        // Sets a new destination and computes the per-tick step size
        void moveTo(double tx, double ty, int travelTicks) {
            targetX = tx;
            targetY = ty;
            if (travelTicks <= 0) {
                renderX = tx; renderY = ty;
                stepX = stepY = 0;
            } else {
                stepX = (tx - renderX) / travelTicks;
                stepY = (ty - renderY) / travelTicks;
            }
        }

        // Teleports the diamond instantly with no animation
        void snapTo(double x, double y) {
            renderX = targetX = x;
            renderY = targetY = y;
            stepX   = stepY   = 0;
        }
    }

    // =========================================================================
    // Constructor / layout
    // =========================================================================

    public SimulatorGUI() {
        instance = this;

        ZoneManager tempZM = null;
        try {
            tempZM = new ZoneManager("sample_zone_file.csv");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null, "Failed to load zone file: " + e.getMessage());
            System.exit(1);
        }
        zoneManager = tempZM;

        JFrame frame = new JFrame("Firefighting Drone Simulator – Iteration 5");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout(4, 4));

        JPanel statsPanel = new JPanel(new BorderLayout(4, 0));
        statsPanel.setBorder(BorderFactory.createTitledBorder("System Monitor"));
        statsPanel.setPreferredSize(new Dimension(0, 155));

        dronePanel.setLayout(new BoxLayout(dronePanel, BoxLayout.Y_AXIS));
        JScrollPane droneScroll = new JScrollPane(dronePanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        droneScroll.setBorder(null);
        statsPanel.add(droneScroll, BorderLayout.CENTER);

        // Legend has a fixed minimum so it can never be squeezed away
        JPanel legend = buildLegendPanel();
        statsPanel.add(legend, BorderLayout.EAST);

        incidentLabel.setFont(new Font("Arial", Font.BOLD, 13));
        incidentLabel.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 0));
        statsPanel.add(incidentLabel, BorderLayout.SOUTH);

        frame.add(statsPanel, BorderLayout.NORTH);

        mapCanvas = new MapCanvas(zoneManager);
        JPanel mapWrapper = new JPanel(new BorderLayout());
        mapWrapper.setBorder(BorderFactory.createTitledBorder("Zone Map"));
        mapWrapper.add(mapCanvas, BorderLayout.CENTER);
        frame.add(mapWrapper, BorderLayout.CENTER);

        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        logArea.setMargin(new Insets(5,5,5,5));
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        JScrollPane logScroll = new JScrollPane(logArea);
        Dimension logDim = new Dimension(250, 0);
        logScroll.setPreferredSize(logDim);
        logScroll.setMinimumSize(logDim);
        JPanel logWrapper = new JPanel(new BorderLayout());
        logWrapper.setBorder(BorderFactory.createTitledBorder("Mission Log"));
        logWrapper.setPreferredSize(new Dimension(260, 0));
        logWrapper.setMinimumSize(new Dimension(260, 0));
        logWrapper.add(logScroll, BorderLayout.CENTER);
        frame.add(logWrapper, BorderLayout.EAST);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));

        JButton startButton = new JButton("Start Simulation");
        startButton.addActionListener(e -> {
            startButton.setEnabled(false);
            log("Simulation started.");
        });
        buttonPanel.add(startButton);

        JButton metricsButton = new JButton("Show Metrics");
        metricsButton.addActionListener(e -> {
            if (MetricsCollector.instance != null) {
                MetricsCollector.instance.printSummary();
            } else {
                log("Metrics not available yet.");
            }
        });
        buttonPanel.add(metricsButton);

        frame.add(buttonPanel, BorderLayout.SOUTH);

        frame.setSize(1150, 740);
        frame.setMinimumSize(new Dimension(950, 600));
        frame.setVisible(true);

        // ── Timer: step all drone positions and repaint every TICK_MS ────
        new javax.swing.Timer(TICK_MS, e -> {
            for (DroneRenderState rs : droneRenderStates.values()) rs.tick();
            mapCanvas.repaint();
        }).start();
    }

    // =========================================================================
    // Legend
    // =========================================================================

    private JPanel buildLegendPanel() {
        JPanel legend = new JPanel();
        legend.setLayout(new BoxLayout(legend, BoxLayout.Y_AXIS));
        legend.setBorder(BorderFactory.createTitledBorder("Legend"));
        legend.setPreferredSize(new Dimension(195, 0));
        legend.setMinimumSize(new Dimension(195, 0));

        DroneState[] ordered = {
                DroneState.IDLE, DroneState.EN_ROUTE, DroneState.ARRIVED,
                DroneState.DROPPING_AGENT, DroneState.RETURNING, DroneState.REFILLING,
                DroneState.SOFT_FAULT, DroneState.HARD_FAULT
        };
        for (DroneState state : ordered) {
            Color c = STATE_COLORS.getOrDefault(state, Color.LIGHT_GRAY);
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

            JLabel swatch = new JLabel("◆");
            swatch.setFont(new Font("Dialog", Font.PLAIN, 13));
            swatch.setForeground(c);
            row.add(swatch);

            JLabel text = new JLabel(state.name().replace('_', ' '));
            text.setFont(new Font("Arial", Font.PLAIN, 11));
            row.add(text);

            legend.add(row);
        }
        return legend;
    }

    // =========================================================================
    // Map Canvas
    // =========================================================================

    private class MapCanvas extends JPanel {
        private final Map<Integer, double[]> zoneBounds = new HashMap<>();
        private final double worldMaxX;
        private final double worldMaxY;
        private static final int PAD  = 24;
        private static final int HALF = 9; // diamond half-size

        private final Image fireImage;

        MapCanvas(ZoneManager zm) {
            setBackground(new Color(235, 238, 245));

            Image img = null;
            java.io.File f = new java.io.File("fire.png");
            if (f.exists()) {
                img = new ImageIcon(f.getAbsolutePath()).getImage();
            } else {
                System.err.println("[MapCanvas] fire.png not found at " + f.getAbsolutePath()
                        + " — fire zones will show tinted background only.");
            }
            fireImage = img;
            double maxX = 0, maxY = 0;
            for (int id = 1; id <= 20; id++) {
                swarm.model.Zone z = zm.getZone(id);
                if (z == null) continue;
                double x1 = z.start().x(), y1 = z.start().y();
                double x2 = z.end().x(),   y2 = z.end().y();
                zoneBounds.put(id, new double[]{x1, y1, x2, y2});
                maxX = Math.max(maxX, Math.max(x1, x2));
                maxY = Math.max(maxY, Math.max(y1, y2));
            }
            worldMaxX = maxX == 0 ? 2100 : maxX;
            worldMaxY = maxY == 0 ? 1500 : maxY;
        }

        private int cx(double wx) {
            return (int) (PAD + (wx / worldMaxX) * (getWidth()  - 2.0 * PAD));
        }
        private int cy(double wy) {
            return (int) (getHeight() - PAD - (wy / worldMaxY) * (getHeight() - 2.0 * PAD));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Zone rectangles
            for (Map.Entry<Integer, double[]> e : zoneBounds.entrySet()) {
                int id = e.getKey(); double[] b = e.getValue();
                boolean fire = zoneFireSeverity.containsKey(id);

                int rx = Math.min(cx(b[0]), cx(b[2]));
                int ry = Math.min(cy(b[3]), cy(b[1]));
                int rw = Math.abs(cx(b[2]) - cx(b[0]));
                int rh = Math.abs(cy(b[1]) - cy(b[3]));

                g2.setColor(fire ? new Color(255, 215, 170) : new Color(210, 222, 242));
                g2.fillRect(rx, ry, rw, rh);
                g2.setStroke(new BasicStroke(fire ? 2.5f : 1.2f));
                g2.setColor(fire ? new Color(200, 75, 0) : new Color(110, 135, 185));
                g2.drawRect(rx, ry, rw, rh);

                g2.setColor(Color.DARK_GRAY);
                g2.setFont(new Font("Arial", Font.BOLD, 12));
                g2.drawString("Z" + id, rx + 5, ry + 15);

                if (fire) {
                    if (fireImage != null) {
                        int iconSize = (int) (Math.min(rw, rh) * 0.55);
                        int imgX = rx + (rw - iconSize) / 2;
                        int imgY = ry + (rh - iconSize) / 2;
                        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        g2.drawImage(fireImage, imgX, imgY, iconSize, iconSize, null);
                    }
                    g2.setColor(new Color(160, 0, 0));
                    g2.setFont(new Font("Arial", Font.BOLD, 11));
                    g2.drawString(zoneFireSeverity.get(id), rx + 5, ry + rh - 5);
                }
            }

            // Base marker
            int bx = cx(0), by = cy(0);
            g2.setColor(new Color(55, 55, 55));
            g2.fillRoundRect(bx - 10, by - 10, 20, 20, 4, 4);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Arial", Font.BOLD, 7));
            g2.drawString("BASE", bx - 11, by + 19);

            // Drone diamonds
            for (Map.Entry<Integer, DroneRenderState> e : droneRenderStates.entrySet()) {
                int droneId = e.getKey();
                DroneRenderState rs = e.getValue();

                Color c = STATE_COLORS.getOrDefault(rs.state, Color.LIGHT_GRAY);
                if      (rs.fault == FaultType.NOZZLE_JAMMED) c = STATE_COLORS.get(DroneState.HARD_FAULT);
                else if (rs.fault != FaultType.NONE)          c = STATE_COLORS.get(DroneState.SOFT_FAULT);

                int dx = cx(rs.renderX), dy = cy(rs.renderY);
                drawDiamond(g2, dx, dy, HALF, c);

                g2.setColor(new Color(255, 255, 255, 200));
                g2.fillRoundRect(dx - 7, dy - HALF - 13, 22, 12, 4, 4);

                g2.setColor(Color.DARK_GRAY);
                g2.setFont(new Font("Arial", Font.BOLD, 10));
                g2.drawString("D" + droneId, dx - 5, dy - HALF - 3);
            }
        }

        private void drawDiamond(Graphics2D g2, int x, int y, int h, Color color) {
            Path2D d = new Path2D.Double();
            d.moveTo(x,     y - h); d.lineTo(x + h, y);
            d.lineTo(x,     y + h); d.lineTo(x - h, y);
            d.closePath();
            g2.setColor(color);       g2.fill(d);
            g2.setColor(color.darker()); g2.setStroke(new BasicStroke(1.5f)); g2.draw(d);
        }
    }

    // =========================================================================
    // Public API – called from subsystem threads
    // =========================================================================

    public void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            String timeStr = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            logArea.append("[" + timeStr + "] " + msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    public void incrementFire() {
        int n = activeIncidents.incrementAndGet();
        SwingUtilities.invokeLater(() -> incidentLabel.setText("Active Incidents: " + n));
    }

    public void decrementFire() {
        if (activeIncidents.get() > 0) {
            int n = activeIncidents.decrementAndGet();
            SwingUtilities.invokeLater(() -> incidentLabel.setText("Active Incidents: " + n));
        }
    }

    /** Updates drone state and kicks off smooth position interpolation toward the destination. */
    public void updateDroneMovement(int droneId, DroneState state, int zoneId,
                                    FaultType fault,
                                    double currentX, double currentY,
                                    double destX,    double destY,
                                    long   travelMs) {
        DroneRenderState rs = droneRenderStates.computeIfAbsent(droneId,
                id -> new DroneRenderState(currentX, currentY));

        rs.state = state;
        rs.fault = fault;

        if (rs.stepX == 0 && rs.stepY == 0) {
            rs.renderX = currentX;
            rs.renderY = currentY;
        }

        int ticks = (int) Math.max(1, travelMs / TICK_MS);
        rs.moveTo(destX, destY, ticks);

        updateMarker(droneId, state, zoneId, fault);
    }

    /** Snap a drone instantly to the base (call this when REFILLING/IDLE). */
    public void snapDroneToBase(int droneId) {
        DroneRenderState rs = droneRenderStates.get(droneId);
        if (rs != null) rs.snapTo(0, 0);
    }

    /** Updates drone state and system monitor marker without changing the map position. */
    public void updateDroneInfo(int droneId, DroneState state, int zoneId, FaultType fault) {
        DroneRenderState rs = droneRenderStates.computeIfAbsent(droneId,
                id -> new DroneRenderState(0, 0));
        rs.state = state;
        rs.fault = fault;
        updateMarker(droneId, state, zoneId, fault);
    }

    public void updateDroneInfo(int droneId, DroneState state, int zoneId) {
        updateDroneInfo(droneId, state, zoneId, FaultType.NONE);
    }

    /** Updates the drone's map position; glides if already animating, snaps if stationary. */
    public void updateDronePosition(int droneId, double x, double y) {
        DroneRenderState rs = droneRenderStates.computeIfAbsent(droneId,
                id -> new DroneRenderState(x, y));
        if (rs.stepX == 0 && rs.stepY == 0) {
            rs.snapTo(x, y);
        } else {
            rs.targetX = x;
            rs.targetY = y;
        }
    }

    public void markDroneFault(int droneId, FaultType fault) {
        DroneRenderState rs = droneRenderStates.get(droneId);
        if (rs != null) rs.fault = fault;
        SwingUtilities.invokeLater(() -> {
            DroneMarker m = droneMarkers.get(droneId);
            if (m == null) return;
            Color  c = fault.isHardFault()
                    ? STATE_COLORS.get(DroneState.HARD_FAULT)
                    : STATE_COLORS.get(DroneState.SOFT_FAULT);
            String lbl = fault.isHardFault()
                    ? "OFFLINE (" + fault + ")"
                    : "FAULTED (" + fault + ")";
            m.update(lbl, c);
        });
    }

    // Updates the agent liters displayed in the drone's status marker ──
    public void updateDroneAgent(int droneId, int agentLiters) {
        SwingUtilities.invokeLater(() -> {
            DroneMarker marker = droneMarkers.get(droneId);
            if (marker != null) marker.updateAgent(agentLiters);
        });
    }

    public void setZoneOnFire(int zoneId, String severity) { zoneFireSeverity.put(zoneId, severity); }
    public void setZoneDrone(int zoneId, int droneId)      { /* diamond movement shows this */        }
    public void clearZone(int zoneId)                      { zoneFireSeverity.remove(zoneId);         }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void updateMarker(int droneId, DroneState state, int zoneId, FaultType fault) {
        SwingUtilities.invokeLater(() -> {
            DroneMarker marker = droneMarkers.computeIfAbsent(droneId, id -> {
                DroneMarker m = new DroneMarker(id);
                dronePanel.add(m);
                dronePanel.revalidate();
                return m;
            });
            Color  c    = colorFor(state, fault);
            String zone = (zoneId != 0) ? " → Zone " + zoneId : "";
            marker.update(state.name() + zone, c);
        });
    }

    private static Color colorFor(DroneState state, FaultType fault) {
        if (fault == FaultType.NOZZLE_JAMMED) return STATE_COLORS.get(DroneState.HARD_FAULT);
        if (fault != FaultType.NONE)          return STATE_COLORS.get(DroneState.SOFT_FAULT);
        return STATE_COLORS.getOrDefault(state, Color.LIGHT_GRAY);
    }

    // =========================================================================
    // DroneMarker inner class
    // =========================================================================

    private static class DroneMarker extends JPanel {
        private final JLabel diamond;
        private final JLabel label;
        private final JLabel agentLabel; // NEW: shows remaining agent liters
        private final int    id;

        DroneMarker(int id) {
            this.id = id;
            setLayout(new FlowLayout(FlowLayout.LEFT, 5, 2));
            setOpaque(true);
            setBackground(new Color(245, 245, 245));
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(220, 220, 220), 1),
                    BorderFactory.createEmptyBorder(2, 5, 2, 5))
            );
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

            diamond = new JLabel("◆");
            diamond.setFont(new Font("Dialog", Font.PLAIN, 17));
            diamond.setForeground(Color.GRAY);
            add(diamond);

            label = new JLabel("Drone " + id + ": IDLE");
            label.setFont(new Font("Arial", Font.BOLD, 12));
            add(label);

            // Agent label, shown in a muted colour so it doesn't compete with state text
            agentLabel = new JLabel("[30L]");
            agentLabel.setFont(new Font("Arial", Font.PLAIN, 11));
            agentLabel.setForeground(new Color(80, 80, 80));
            add(agentLabel);
        }

        void update(String stateText, Color color) {
            diamond.setForeground(color);
            label.setText("Drone " + id + ": " + stateText);
            int r = (color.getRed()   + 9 * 245) / 10;
            int g = (color.getGreen() + 9 * 245) / 10;
            int b = (color.getBlue()  + 9 * 245) / 10;
            setBackground(new Color(Math.min(255,r), Math.min(255,g), Math.min(255,b)));
            repaint();
        }

        // Called whenever the Scheduler's droneAgentLiters map changes for this drone
        void updateAgent(int liters) {
            agentLabel.setText("[" + liters + "L]");
            repaint();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(SimulatorGUI::new);
    }
}
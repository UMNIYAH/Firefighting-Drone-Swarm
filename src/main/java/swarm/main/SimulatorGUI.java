import javax.swing.*;
import java.awt.*;

public class SimulatorGUI {

    private JTextArea logArea;

    public SimulatorGUI() {
        JFrame frame = new JFrame("Firefighting Drone Simulator – Iteration 1");
        frame.setSize(600, 400);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        logArea = new JTextArea();
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);

        JButton startButton = new JButton("Start Simulation");
        startButton.addActionListener(e -> log("Simulation started."));

        frame.add(scrollPane, BorderLayout.CENTER);
        frame.add(startButton, BorderLayout.SOUTH);

        frame.setVisible(true);
    }

    public void log(String message) {
        logArea.append(message + "\n");
    }

    public static void main(String[] args) {
        new SimulatorGUI();
    }
}

package swarm.infra;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

/**
 * A simple wrapper to handle UDP sending and receiving
 */
public class UDPHelper {

    private final DatagramSocket socket;

    // Constructor for Scheduler and DroneSubsytem (need specific port)
    public UDPHelper(int listeningPort) throws SocketException {
        socket = new DatagramSocket(listeningPort);
    }

    // Constructor for FireIncidentSubsystem (doesn't care about own port)
    public UDPHelper() throws SocketException {
        socket = new DatagramSocket();
    }

    // Sends a formatted string message to a specific port
    public void send(String message, int destinationPort) throws Exception {
        byte[] data = message.getBytes();

        // Uses localhost
        DatagramPacket packet = new DatagramPacket(
                data,
                data.length,
                InetAddress.getLocalHost(),
                destinationPort
        );
        socket.send(packet);
    }

    public String receive() throws Exception {
        byte[] buffer = new byte[1024];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);

        // Convert the byte array to a String
        return new String(packet.getData(), 0, packet.getLength()).trim();
    }

    public void close() {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}
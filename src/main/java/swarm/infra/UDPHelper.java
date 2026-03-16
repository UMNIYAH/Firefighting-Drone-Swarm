package swarm.infra;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

/**
 * A simple wrapper to handle UDP sending and receiving.
 * Supports configurable destination host for multi-machine deployment (I3).
 */
public class UDPHelper {

    private final DatagramSocket socket;

    // Constructor for Scheduler and DroneSubsystem (need specific port)
    public UDPHelper(int listeningPort) throws SocketException {
        socket = new DatagramSocket(listeningPort);
    }

    // Constructor for FireIncidentSubsystem (doesn't care about own port)
    public UDPHelper() throws SocketException {
        socket = new DatagramSocket();
    }

    // Sends to a specific host and port (for multi-machine)
    public void send(String message, String host, int destinationPort) throws Exception {
        byte[] data = message.getBytes();
        DatagramPacket packet = new DatagramPacket(
                data, data.length,
                InetAddress.getByName(host),
                destinationPort
        );
        socket.send(packet);
    }

    // Convenience: sends to localhost (backwards compatible)
    public void send(String message, int destinationPort) throws Exception {
        send(message, "localhost", destinationPort);
    }

    public String receive() throws Exception {
        byte[] buffer = new byte[1024];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);
        return new String(packet.getData(), 0, packet.getLength()).trim();
    }

    public void close() {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}
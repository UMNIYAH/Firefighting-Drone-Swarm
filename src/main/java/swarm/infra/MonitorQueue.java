package swarm.infra;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Monitor-style blocking queue using synchronized + wait/notifyAll.
 * Thread-safe blocking queue; avoids busy-waiting and provides condition synchronization.
 */
public class MonitorQueue<T> {
    private final Deque<T> buffer = new ArrayDeque<>();
    private final int capacity;

    public MonitorQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
    }

    public synchronized void put(T item) throws InterruptedException {
        while (buffer.size() >= capacity) {
            wait(); // wait until space available
        }
        buffer.addLast(item);
        notifyAll(); // wake consumers
    }

    public synchronized T take() throws InterruptedException {
        while (buffer.isEmpty()) {
            wait(); // wait until item available
        }
        T item = buffer.removeFirst();
        notifyAll(); // wake producers
        return item;
    }

    public synchronized int size() {
        return buffer.size();
    }
}

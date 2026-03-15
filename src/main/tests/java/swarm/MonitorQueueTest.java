package swarm;

import swarm.infra.MonitorQueue;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class MonitorQueueTest {

    @Test
    public void testBasicPutAndTake() throws InterruptedException {
        MonitorQueue<String> queue = new MonitorQueue<>(5);
        queue.put("test");
        assertEquals("test", queue.take());
    }

    @Test
    public void testSize() throws InterruptedException {
        MonitorQueue<Integer> queue = new MonitorQueue<>(10);
        queue.put(1);
        queue.put(2);
        assertEquals(2, queue.size());
        queue.take();
        assertEquals(1, queue.size());
    }
}
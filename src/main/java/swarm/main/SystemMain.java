package swarm.main;

import swarm.infra.MessageBus;
import swarm.messages.*;

public class SystemMain {

    public static void main(String[] args) {
        MessageBus bus = new MessageBus(50);

        // Temporary demo producer (FireIncident stand-in)
        Thread fireProducer = new Thread(() -> {
            try {
                bus.fireEvents.put(new FireEvent(
                        System.currentTimeMillis(),
                        1,
                        EventType.FIRE_DETECTED,
                        Severity.MODERATE
                ));
                bus.fireEvents.put(new FireEvent(
                        System.currentTimeMillis(),
                        2,
                        EventType.DRONE_REQUEST,
                        Severity.LOW
                ));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "FireIncidentStub");

        // Temporary demo consumer/producer (Scheduler stand-in)
        Thread schedulerStub = new Thread(() -> {
            try {
                while (true) {
                    FireEvent ev = bus.fireEvents.take();
                    System.out.println("[Scheduler] got event: " + ev);

                    // Minimal “dispatch”: always send drone 1
                    bus.droneCommands.put(new DroneCommand(1, ev.zoneId(), ev.severity()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "SchedulerStub");

        // Temporary demo consumer/producer (Drone stand-in)
        Thread droneStub = new Thread(() -> {
            try {
                while (true) {
                    DroneCommand cmd = bus.droneCommands.take();
                    System.out.println("[Drone] got command: " + cmd);

                    bus.droneStatuses.put(new DroneStatus(cmd.droneId(), "EN_ROUTE", cmd.zoneId()));
                    bus.droneStatuses.put(new DroneStatus(cmd.droneId(), "ARRIVED", cmd.zoneId()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "DroneStub");

        // Temporary demo consumer (Scheduler status receiver stand-in)
        Thread statusConsumer = new Thread(() -> {
            try {
                while (true) {
                    DroneStatus st = bus.droneStatuses.take();
                    System.out.println("[Scheduler] got status: " + st);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "StatusConsumerStub");

        schedulerStub.start();
        droneStub.start();
        statusConsumer.start();
        fireProducer.start();
    }
}
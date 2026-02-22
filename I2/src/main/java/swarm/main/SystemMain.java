package swarm.main;

import swarm.infra.MessageBus;
import swarm.messages.*;
import swarm.subsystems.FireIncidentSubsystem;

public class SystemMain {

    public static void main(String[] args) {
        MessageBus bus = new MessageBus(50);

        FireIncidentSubsystem fireSubsystem = new FireIncidentSubsystem(bus,"Sample_event_file.csv");
        Thread fireThread = new Thread(fireSubsystem, "FireIncidentThread");

        // Temporary demo consumer/producer (Scheduler stand-in)
        Thread schedulerStub = new Thread(() -> {
            try {
                while (true) {
                    FireEvent ev = bus.fireEvents.take();
                    System.out.println("[Scheduler] got event: " + ev);

                    // Minimal dispatch: always send drone 1
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

                    bus.droneStatuses.put(new DroneStatus(cmd.droneId(), DroneState.EN_ROUTE, cmd.zoneId(), 30));
                    bus.droneStatuses.put(new DroneStatus(cmd.droneId(), DroneState.ARRIVED, cmd.zoneId(), 30));
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
        //fireProducer.start();
        fireThread.start();
    }
}
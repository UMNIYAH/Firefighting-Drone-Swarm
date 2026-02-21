package swarm.main;

import swarm.infra.MessageBus;
import swarm.infra.ZoneManager;
import swarm.messages.*;
import swarm.subsystems.DroneSubsystem;
import swarm.subsystems.FireIncidentSubsystem;
import swarm.subsystems.Scheduler;

import java.io.IOException;

public class SystemMain {

    public static void main(String[] args) throws IOException {

        MessageBus bus = new MessageBus(50);
        ZoneManager zoneManager = new ZoneManager("sample_zone_file.csv");

        FireIncidentSubsystem fireSubsystem = new FireIncidentSubsystem(bus,"Sample_event_file.csv");
        Thread fireThread = new Thread(fireSubsystem, "FireIncidentThread");

        Scheduler scheduler = new Scheduler(bus);
        Thread schedulerThread = new Thread(scheduler,"SchedulerThread");

        DroneSubsystem droneSubsystem = new DroneSubsystem(bus,1,zoneManager);
        Thread droneThread = new Thread(droneSubsystem, "DroneThread");


        fireThread.start();
        schedulerThread.start();
        droneThread.start();
    }
}
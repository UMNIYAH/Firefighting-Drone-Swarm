# Firefighting-Drone-Swarm
A modular real-time concurrent system in Java simulating a distributed firefighting drone swarm; it emphasizes inter-subsystem communication, event-driven design, and scalability toward multi-process UDP-based deployment.

### Development Process
The project follows an iterative and incremental development process. GitHub Issues and Pull Requests are used to track development, and merging into the main branch requires two approvals.

### Team Members
- [Umniyah Mohammed](https://github.com/UMNIYAH)
- [Armin Mozafari]()
- [Liam Perreault]()
- [Vincent Nguyen]()

### Team Contribution - Iteration 1
#### Umniyah
#### Armin
- Implemented the Test files:
  - DroneSubsystemTest
  - FireIncidentSubsystemTest
  - IntegrationTest
  - MessageStructureTest
  - MonitorQueueTest
  - SchedulerTest

- Designed and Modeled the UML class diagram

#### Liam
- Designed and implemented the `FireIncidentSubsystem`
  -  Reads fire events from csv file.
  -  publishes `FireEvent` messages to the `MessageBus`
  -  Listens for `DroneStatus` updates and prints drone state and drone 
  -  Separate threads for reading events and handling statuses.

- Designed and implemented the `DroneSubsystem`
  -  Listens for `Dronecommand` messages from Scheduler. 
  -  Simulates drone en route and extinguishing actions
  -  Published `DroneStatus` updates back to the `MessageBus`

#### Vincent

### Documentation and Submissions
Deliverables will be added as the project progresses.
- [Iteration 0](/I0)
- [Iteration 1](/I1)
- [Iteration 2](/I2)
- [Iteration 3](/I3)
- [Iteration 4](/I4)
- [Iteration 5](/I5)
- [Final Report](/Final%20Project%20Submission)

# Firefighting Drone Swarm

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
#### Liam
#### Vincent

### Team Contribution - Iteration 2
#### Umniyah
#### Armin
#### Liam
#### Vincent

### Team Contribution - Iteration 3
#### Umniyah
- Refactored codebase structure and cleaned up subsystem packages ([#27](https://github.com/UMNIYAH/Firefighting-Drone-Swarm/pull/27))
- Implemented multi-drone support: Scheduler with mission queue, per-drone UDP ports, and one-click Launcher ([#28](https://github.com/UMNIYAH/Firefighting-Drone-Swarm/pull/28))
- Added proximity-based dispatch, load balancing, drone position tracking, and per-drone GUI updates ([#29](https://github.com/UMNIYAH/Firefighting-Drone-Swarm/pull/29))
#### Armin
- I3 implementation features
#### Liam
- Documentation
#### Vincent
- Testing

### Team Contribution - Iteration 4
#### Umniyah
- Defined fault types and extended the data model
- Extended input file format to support fault injection
- Submitted work to deliverables directories
- Updated README
- Updated UML diagrams
#### Armin
- Rebuilt SimulatorGUI with a custom-painted MapCanvas replacing the old grid layout
- Added smooth interpolated drone movement with color-coded diamond markers
- Added state color legend and timestamped log entries
- Added white background behind drone text for readability
#### Liam
- Implemented validation testing for fault handling
#### Vincent
- Implemented fault injection in DroneSubsystem (DRONE_STUCK, NOZZLE_JAMMED, PACKET_LOSS)
- Added watchdog timer, hard/soft fault handling, and mission re-queuing in Scheduler
- Added en-route interception logic for returning drones
- Updated zone CSV to full 3×3 grid (9 zones)

---
## Setup Instructions

### Requirements
* Java JDK 17 (or compatible)
* IntelliJ IDEA

### Project Setup
#### Option 1 – Download ZIP

1. Extract the assignment from the ZIP file.
2. Navigate to the extracted project folder.

#### Option 2 – Clone from GitHub

```
git clone https://github.com/UMNIYAH/Firefighting-Drone-Swarm.git
```

#### Open the Project

1. Open the project in IntelliJ IDEA.
2. Configure the JDK if prompted.
3. Build the project using IntelliJ build tools.

---

## Running the System

### Option 1 – Using the Launcher (recommended)

1. Run `Launcher.java`
2. To run with multiple drones, set the program argument to the desired count (e.g., `3`). Defaults to 1.

### Option 2 – Running subsystems manually (in this order)

1. Run `SimulatorGUI.java` - wait for the GUI window to appear
2. Run `Scheduler.java` - wait for `[Scheduler] Listening on port 5000...`
3. Run `DroneSubsystem.java` - wait for `[Drone 1] Listening on port 6001...`
4. Run `FireIncidentSubsystem.java` - run **last**, it sends events immediately

> **Note:** When running manually, each subsystem has its own `main()` and communicates via UDP on `localhost`. The Scheduler listens on port 5000, and each drone listens on port `6000 + droneId`.

---

## Testing Instructions

1. Open the test directory under `Deliverables/I2/src/main/tests/` in IntelliJ.
2. Right-click the test package.
3. Select **Run All Tests**.

---

## System Architecture

All subsystems communicate via UDP using a simple text-based protocol:
* `FIRE:<zoneId>:<severity>:<eventType>` - Fire events sent to Scheduler (port 5000)
* `CMD:<zoneId>:<severity>` - Commands sent from Scheduler to Drone (port 6000 + droneId)
* `STATUS:<droneId>:<state>:<zoneId>:<posX>:<posY>` - Status updates sent from Drone to Scheduler (port 5000)

---

## System Components

### Scheduler

The Scheduler acts as the central coordination system.

Responsibilities:
* Receives fire events from the Fire Incident Subsystem via UDP
* Maintains a mission queue for pending fire incidents
* Dispatches the **closest idle drone** to the fire zone
* Balances workload across drones using completed mission count as a tiebreaker
* Tracks drone positions, states, and ports
* Receives drone status updates and re-dispatches queued missions when drones become idle

### Drone Subsystem

Each `DroneSubsystem` instance simulates an individual drone.

Responsibilities:
* Listens for commands from the Scheduler on its own UDP port (`6000 + droneId`)
* Simulates travel to fire zones with distance-based flight time
* Simulates firefighting agent deployment (door cycle + flow rate)
* Returns to base and refills agent capacity
* Reports status and position back to the Scheduler after each state transition
* Drone state lifecycle: `IDLE → EN_ROUTE → ARRIVED → DROPPING_AGENT → RETURNING → REFILLING → IDLE`

### Fire Incident Subsystem

Reads fire events from a CSV file and sends them to the Scheduler.

Responsibilities:
* Parses fire event data (time, zone, event type, severity) from CSV input
* Serializes events and sends them to the Scheduler via UDP (port 5000)
* Supports configurable input files

### Simulator GUI

Provides a real-time visual display of the system.

Responsibilities:
* Displays a 3×3 zone map with fire indicators and severity labels
* Shows which drone is assigned to each active fire zone
* Displays per-drone state and current zone assignment
* Tracks active incident count
* Logs all subsystem events in a scrollable log panel

### Launcher

One-click entry point that starts all subsystems in the correct order.

Responsibilities:
* Starts GUI → Scheduler → Drone(s) → FireIncidentSubsystem with proper timing delays
* Ensures each subsystem's UDP port is bound before dependent subsystems start
* Supports configurable drone count via command-line argument

---

## Project Structure
```
Firefighting-Drone-Swarm/
│
├── Deliverables/             
├── Diagrams/                      
├── src/
│   └── main/
│       └── java/
│           └── swarm/
│               ├── infra/         
│               │   ├── DroneConfig.java
│               │   ├── MonitorQueue.java
│               │   ├── UDPHelper.java
│               │   └── ZoneManager.java
│               │
│               ├── main/          
│               │   ├── Launcher.java
│               │   └── SimulatorGUI.java
│               │
│               ├── messages/      
│               │   ├── DroneCommand.java
│               │   ├── DroneState.java
│               │   ├── DroneStatus.java
│               │   ├── EventType.java
│               │   ├── FireEvent.java
│               │   └── Severity.java
│               │
│               ├── model/         
│               │   ├── Position.java
│               │   └── Zone.java
│               │
│               └── subsystems/    
│                   ├── DroneSubsystem.java
│                   ├── FireIncidentSubsystem.java
│                   └── Scheduler.java
│
├── .gitignore                     # Git ignore configuration
├── fire.png                       # GUI image asset
├── Project Specification.txt      # Assignment description
├── README.md                      # Project documentation
├── Sample input files.txt         # Description of input file formats
├── Sample_event_file.csv          # Sample fire incident events
├── sample_zone_file.csv           # Zone configuration file
└── SYSC3303A W26 Project V2.1.pdf # Official project specification
```
---

## Documentation and Submissions

Deliverables will be added as the project progresses.

* [Iteration 0](/I0)
* [Iteration 1](/I1)
* [Iteration 2](/I2)
* [Iteration 3](/I3)
* [Iteration 4](/I4)
* [Iteration 5](/I5)
* [Final Report](/Final%20Project%20Submission)

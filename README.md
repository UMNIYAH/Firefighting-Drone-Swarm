# Firefighting Drone Swarm Simulator

**SYSC 3303 Real-Time Concurrent Systems**\
**Iteration 3  README**

A modular real-time concurrent system in Java simulating a distributed firefighting drone swarm; it emphasizes inter-subsystem communication, event-driven design, and scalability toward multi-process UDP-based deployment.

---

## Team Members

* Umniyah Mohammed
* Armin Mozafari
* Liam Perreault
* Vincent Nguyen

---
## Setup Instructions

**Requirements**

* Java JDK 17 (or compatible)
* IntelliJ IDEA

**Project Setup**

### Option 1 – Download ZIP

1. Extract the assignment from the ZIP file.
2. Navigate to the extracted project folder.

### Option 2 – Clone from GitHub

1. Clone the repository:

```
git clone https://github.com/UMNIYAH/Firefighting-Drone-Swarm.git
```

2. Navigate to the project directory.

### Open the Project

1. Open the project in IntelliJ IDEA.
2. Configure the JDK if prompted.
3. Build the project using IntelliJ build tools.

---
## Running the System

### Option 1 - using the launcher
1. Run Launcher.java

### Option 2 - running subsystems manually


1. Run SimulatorGUI.java
2. Run the Scheduler.java
3. Run the DroneSubsystem.java
4. Run the FireIncidentSubsystem.java

---

## Testing Instructions

Running Tests

1. Open the test directory in IntelliJ.
2. Right-click the test package
3. Select Run All Tests

---
## System Components

### Scheduler

The Scheduler acts as the central control system.

Responsibilities:

* Receives fire requests from the Fire Incident Subsystem
* Determines which drone should respond
* Dispatches commands to drones
* Receives drone status updates
* Monitors drone states and incident progress

The Scheduler distributes work among drones to balance the workload and reduce fire response time.

### Drone Subsystem

Each DroneSubsystem instance simulates an individual drone.

Responsibilities:

* Receives commands from the Scheduler
* Simulates travel to fire zones
* Simulates firefighting agent deployment
* Returns to base and refills when necessary
* Sends status updates to the Scheduler

Drone state machine states include:

* IDLE
* EN_ROUTE
* DROPPING_AGENT
* RETURNING
* REFILLING

Each drone maintains an internal state, including:

* current position
* remaining firefighting agent
* mission status

### Fire Incident Subsystem

The Fire Incident Subsystem simulates fire events occurring in different zones.

Responsibilities:

* Reads fire incidents from a CSV input file
* Generates fire requests
* Sends fire events to the Scheduler

Each fire request includes:

* zone ID
* fire severity
* event timestamp

## Infrastructure Components

### UDPHelper

Provides a simplified interface for sending and receiving UDP packets between subsystems.

Responsibilities include:

* sending UDP datagrams
* receiving UDP messages
* handling socket communication

### ZoneManager

Loads zone configuration data from a CSV file and provides zone location information used by drones for navigation.

### Simulator GUI

The GUI provides a visualization of the system, including:

* fire incidents across zones
* drone state changes
* system event logs
* active incident counter

The GUI acts as a monitoring interface for the simulation.

---

## Simulation Configuration

The simulation is configurable using input files and constants.

Examples include:

* number of drones
* number of fire zones
* drone agent capacity
* travel speed
* firefighting agent drop time

These parameters allow the simulation to model different operational scenarios.

---


## Deliverables Iteration 3

* Java source files (.java)
* Test files
* UML class and sequence diagrams
* IntelliJ project configuration files
* README.txt

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
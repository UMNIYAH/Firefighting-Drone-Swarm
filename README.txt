# Firefighting Drone Swarm Simulator

**Iteration 1 – README**

## Project Description

This project is a modular real-time concurrent system in Java simulating a distributed firefighting drone swarm. The system emphasizes inter-subsystem communication, event-driven design, and scalability toward future multi-process UDP-based deployment.

---

## Team Members

* Umniyah Mohammed
* Armin Mozafari
* Liam Perreault
* Vincent Nguyen

---

## System Overview (Iteration 1)

### Subsystems

* **Fire Incident Subsystem**
  Reads fire events from an input file and sends structured fire events.

* **Scheduler Subsystem**
  Receives fire events, dispatches drone commands, and processes drone status updates.

* **Drone Subsystem**
  Simulates drone travel and fire extinguishing and reports status updates.

### Infrastructure

* **MessageBus**
  Centralized communication layer decoupling all subsystems.

* **MonitorQueue**
  Thread-safe queue enabling inter-thread communication.

---

## Setup Instructions

### Requirements

* Java JDK 17 (or compatible)
* IntelliJ IDEA
* Git

### Project Setup

1. Clone the repository:

   ```bash
   git clone <repository-url>
   ```
2. Open the project in IntelliJ IDEA
3. Configure the JDK if prompted
4. Build the project using IntelliJ build tools

---

## Running the System

### System Execution

Run the system entry point (`SystemMain.java`) to start:

* Fire Incident Subsystem
* Scheduler
* Drone Subsystem
* Shared MessageBus

Subsystems execute concurrently and communicate through the MessageBus.

---

## Testing Instructions

### Unit Tests

Unit tests are provided for:

* MessageBus
* MonitorQueue
* Message types (FireEvent, DroneCommand, DroneStatus)
* Scheduler logic

### Integration Testing

An integration test validates end-to-end communication between all subsystems.

### Running Tests

1. Open the test directory in IntelliJ
2. Right-click the test package
3. Select **Run All Tests**

---

## Deliverables – Iteration 1

* Java source files (.java)
* Test files
* UML class and sequence diagrams
* IntelliJ project configuration files
* README.txt
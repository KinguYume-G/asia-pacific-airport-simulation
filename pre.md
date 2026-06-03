# Video Presentation Guide

## Official Video Requirements

According to the assignment handout, the system submission zip must include a
video presentation of the simulation running.

Required video conditions:

- Duration: minimum 3 minutes and maximum 5 minutes per person.
- The video must show the airport simulation running.
- The video must simulate the required scenarios stated in the assignment.
- The video must show which requirements are met in the output.
- The video must show the corresponding code for those requirements.

The video should therefore not only run the program. It should connect:

1. Requirement
2. Source code implementation
3. Runtime output evidence

Important points to show:

- ATC is a real thread, and ATC decisions come from `[Thread-ATC]`.
- There is only one runway, protected by synchronized ATC state.
- Gates are pre-reserved before landing, so planes do not wait on the ground.
- Plane-3 and Plane-4 wait in the air while both gates are occupied.
- Plane-5 is the emergency plane and receives priority through `emergencyQueue`.
- One refuelling truck is protected by `ReentrantLock(true)`.
- Passenger, supply/cleaning, and refuelling tasks run concurrently.
- Final statistics show all gates are empty, 6 planes served, passengers boarded,
  and max/min/average waiting time.

## Suggested Recording Flow

### 0:00 - 0:25: Introduction

Show the project folder and introduce the system:

- Project name: Asia Pacific Airport Simulation
- Module: CT074-3-2 Concurrent Programming
- Language: Java
- Main goal: simulate airport operations with explicit threads and synchronization

### 0:25 - 1:00: Code Structure

Open the key source files:

- `AirportSimulation.java`: main entry point and arrival mode
- `ATC.java`: real ATC thread and scheduler
- `Airplane.java`: airplane lifecycle
- `RefuellingTruck.java`: single truck lock
- `Statistics.java`: final sanity checks and statistics

Say that restricted automatic concurrency libraries are not used:

- No `ExecutorService`
- No `ForkJoinPool`
- No `CompletableFuture`
- No `parallelStream`
- No `PriorityBlockingQueue`

### 1:00 - 1:50: Basic Requirements in Code

Show these parts:

- `ATC extends Thread`
- `synchronized` ATC methods and run loop
- `tryDispatchLanding()`
- `canLand = !runway.isOccupied() && gate != null && groundCount < 3`
- `gate.setOccupied(true)` before `req.grant(gate)`

Explain:

- One runway is used mutually exclusively.
- Ground capacity is limited by `groundCount < 3`.
- A gate is reserved before landing, so there is no ground waiting area.

### 1:50 - 2:30: Additional Requirements in Code

Show:

- `emergencyQueue` and `landingQueue` in `ATC.java`
- Emergency queue selected before normal queue
- `RefuellingTruck.java` with `ReentrantLock(true)`
- `AirportSimulation.java` controlled scenario schedule

Explain:

- Plane-5 is marked emergency.
- Emergency priority is application-level scheduling, not `Thread.setPriority()`.
- Only one plane can refuel at a time.

### 2:30 - 3:50: Run the Simulation

Compile and run:

```text
javac *.java
java AirportSimulation
```

Point out these output lines:

```text
Mode: Controlled congested scenario
ATC: Plane-3 - Runway busy and all gates occupied. Holding in air.
ATC: Plane-5 [EMERGENCY] - All gates occupied. Holding in air.
ATC: Landing Permission granted for Plane-5 [EMERGENCY - PRIORITY].
```

Also point out:

- Passenger threads: `[Thread-Passenger-...]`
- Refuel threads waiting for and using the fuel truck
- Supply/cleaning running with passengers and refuelling

### 3:50 - 4:40: Final Statistics and Sanity Check

At the end of the output, show:

```text
Gate-1 : Empty (OK)
Gate-2 : Empty (OK)
Result : All gates empty = true
Planes Served : 6
Passengers Boarded
Max Waiting Time
Min Waiting Time
Avg Waiting Time
```

Explain that this proves the simulation completed correctly and all aircraft left
the airport.

### 4:40 - 5:00: Conclusion

Summarize:

- All basic requirements are met.
- Additional emergency and refuelling requirements are met.
- The implementation uses course-taught mechanisms: `Thread`, `synchronized`,
  `wait()`, `notifyAll()`, `join()`, `ReentrantLock`, `AtomicInteger`, and
  `volatile`.

## Quick Recording Checklist

Before recording:

- Set terminal font size large enough to read.
- Open the project folder in the IDE.
- Keep these files ready in tabs:
  - `AirportSimulation.java`
  - `ATC.java`
  - `Airplane.java`
  - `RefuellingTruck.java`
  - `Statistics.java`
- Make sure `RANDOM_MODE = false` for controlled congested scenario.
- Run `javac *.java` first to prove compilation.
- Run `java AirportSimulation`.
- Keep video between 3 and 5 minutes.

## Presentation Script

Hello, my name is [Your Name]. In this video, I will present my Java concurrent
programming assignment, the Asia Pacific Airport Simulation.

The purpose of this system is to simulate a small airport with one runway, two
gates, one refuelling truck, and six airplanes. This is a concurrent programming
problem because multiple airplanes can arrive and wait at the same time, while
passengers, cleaning, supply restocking, and refuelling can happen concurrently.
At the same time, shared resources such as the runway, gates, and refuelling truck
must be protected to avoid race conditions and unsafe behaviour.

First, I will show the main code structure. `AirportSimulation.java` is the main
entry point. It creates the runway, gates, refuelling truck, statistics object,
ATC thread, and the six airplane threads. In this file, `RANDOM_MODE` can be set
to true for random 0, 1, or 2 second arrivals. For this demonstration, it is set
to false, so the controlled congested scenario is reproduced every time.

Next, `ATC.java` is the most important class. ATC extends `Thread`, so it is a
real independent thread. This is important because the assignment says that
objects are not processes. Therefore, ATC decisions must be printed by
`[Thread-ATC]`, not by an airplane thread.

Inside ATC, the runway, gates, queues, and ground count are protected using
`synchronized`. The method `tryDispatchLanding()` checks three conditions before
granting landing permission: the runway must be free, a gate must be available,
and `groundCount` must be less than 3. The gate is reserved before the airplane
is allowed to land. This means the plane never lands and waits on the ground for
a gate.

For communication, I use `LandingRequest` and `TakeoffRequest`. The airplane
submits a request and then waits on the request object using `wait()`. The ATC
thread later calls `grant()` and uses `notifyAll()` to wake the airplane. This
prevents the airplane from holding the ATC lock while waiting.

The additional emergency requirement is implemented using two queues:
`emergencyQueue` and `landingQueue`. ATC always checks the emergency queue first.
Plane-5 is the emergency plane, so when it arrives with fuel shortage, it receives
priority over normal planes already waiting. This is not implemented using
`Thread.setPriority()`, because Java does not guarantee that high-priority
threads run first. The priority is controlled by my own ATC scheduling logic.

The single refuelling truck is implemented in `RefuellingTruck.java`. It uses
`ReentrantLock(true)`, which means only one plane can refuel at a time, and the
fair lock helps avoid starvation. The lock is released in a `finally` block, so
it will always be released even if an interruption happens.

Now I will compile and run the program. I use `javac *.java`, and then
`java AirportSimulation`.

In the output, we can see the mode is controlled congested scenario. We can also
see Plane-3 holding in the air because the runway is busy and all gates are
occupied. Plane-5 is marked as emergency and also waits while all gates are
occupied. When a gate becomes available, ATC grants landing permission to
Plane-5 with `[EMERGENCY - PRIORITY]` before the normal waiting planes. This
proves that the emergency scenario is working.

The output also shows passenger threads such as `[Thread-Passenger-...]`,
supply threads, and refuelling threads. These prove that passengers,
supply/cleaning, and refuelling are running concurrently during gate service.
The refuelling output shows that planes wait for the fuel truck, proving that
only one refuelling truck is being shared.

At the end, the final statistics show that Gate-1 and Gate-2 are both empty, and
the sanity check says all gates empty is true. The program also prints that 6
planes were served, the number of passengers boarded, and the maximum, minimum,
and average waiting time.

In conclusion, this simulation meets the basic requirements and additional
requirements. It uses the concurrent programming concepts taught in the module:
`Thread`, `synchronized`, `wait()`, `notifyAll()`, `join()`, `ReentrantLock`,
`AtomicInteger`, and `volatile`. Thank you.

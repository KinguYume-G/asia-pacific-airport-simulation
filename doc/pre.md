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

### 2:30 - 4:00: Run the Simulation

Compile and run:

```text
javac *.java
java AirportSimulation
```

CONGESTED SCENARIO -- point out this sequence in order:

```text
[Thread-ATC] ATC: Plane-3 - All gates occupied. Holding in air.
```
Say: "Plane-3 arrived at t=2s and is already waiting in the queue."

```text
[Thread-ATC] ATC: Plane-4 - Runway busy and all gates occupied. Holding in air.
```
Say: "Plane-4 arrived at t=3s -- now two normal planes are waiting in the air,
and both gates are still occupied by Planes 1 and 2."

```text
[Thread-ATC] ATC: Plane-5 [EMERGENCY] - All gates occupied. Holding in air.
```
Say: "Plane-5 with fuel shortage arrives at t=4s -- this is exactly the congested
scenario from the assignment: two planes already waiting, both gates occupied,
emergency plane arrives."

```text
[Thread-ATC] ATC: Landing Permission granted for Plane-5 [EMERGENCY - PRIORITY].
[Thread-ATC] ATC: Landing Permission granted for Plane-3.
[Thread-ATC] ATC: Plane-4 - Runway busy and all gates occupied. Holding in air.
```
Say: "When a gate frees, ATC grants Plane-5 first -- before Plane-3 and Plane-4
who arrived earlier. This proves emergency priority works."

CONCURRENT GATE SERVICES -- scroll to a section where one plane is at a gate,
then pause and point out:

```text
[Thread-Passenger-P2-3]  Passenger-3: Disembarking from Plane-2.
[Thread-Supply-Plane-2]  Plane-2: Restocking supplies and cleaning aircraft...
[Thread-Refuel-Plane-2]  RefuellingTruck: Refuelling Plane-2...
```
Say: "Notice these three thread types appear simultaneously -- passenger,
supply/cleaning, and refuelling are all running at the same time, proving
concurrent gate services."

REFUELLING TRUCK MUTEX -- point out:

```text
[Thread-Refuel-Plane-2]  RefuellingTruck: Plane-2 waiting for fuel truck...
[Thread-Refuel-Plane-3]  RefuellingTruck: Refuelling complete for Plane-2.
[Thread-Refuel-Plane-3]  RefuellingTruck: Refuelling Plane-3...
```
Say: "Plane-3 must wait until Plane-2 finishes -- only one truck, protected
by ReentrantLock."

### 4:00 - 4:40: Final Statistics and Sanity Check

At the end of the output, show:

```text
Gate-1 : Empty  (OK)
Gate-2 : Empty  (OK)
Result : All gates empty = true
Planes Served         : 6
Passengers Boarded    : [number]
Max Waiting Time (ms) : [number]
Min Waiting Time (ms) : [number]
Avg Waiting Time (ms) : [number]
```

Say: "The sanity check confirms both gates are empty -- no plane is stuck.
Six planes were served. The waiting time statistics show max, min, and average
wait from landing request to landing permission."

### 4:40 - 5:00: Conclusion

Summarize:

- All basic requirements are met.
- Additional emergency and refuelling requirements are met.
- The implementation uses course-taught mechanisms: `Thread`, `synchronized`,
  `wait()`, `notifyAll()`, `join()`, `ReentrantLock`, `AtomicInteger`, and
  `volatile`.

## Quick Recording Checklist

Before recording:

- Set terminal and IDE font size large enough to read clearly on video.
- Open the project folder in the IDE with these files ready in tabs:
  - `AirportSimulation.java`  (show RANDOM_MODE = false)
  - `ATC.java`                (show extends Thread, emergencyQueue, tryDispatchLanding)
  - `Airplane.java`           (show performGateServices, 3 concurrent threads)
  - `RefuellingTruck.java`    (show ReentrantLock(true), lockInterruptibly, finally)
  - `Statistics.java`         (show printFinalStats)
- Confirm `RANDOM_MODE = false` in AirportSimulation.java before recording.
- Do one practice run to confirm the congested scenario output appears as expected.
- Scroll speed: slow enough for the output to be readable on screen.
- After program finishes, scroll up to show the congested scenario lines,
  then scroll down to show the final statistics.
- Target total video length: 4:30 to 4:50. Stop before 5:00.

Timing guide:
  0:00 - 0:25  Introduction
  0:25 - 1:00  Code structure (show file list, confirm no restricted libraries)
  1:00 - 1:50  Basic requirements in code
  1:50 - 2:30  Additional requirements in code
  2:30 - 4:00  Run simulation + point out congested scenario + concurrent services
  4:00 - 4:40  Final statistics and sanity check
  4:40 - 5:00  Conclusion

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

The output starts with "Mode: Controlled congested scenario." This confirms
the deterministic schedule is active, so the required congested scenario
is reproduced every time.

Now I will point out the congested scenario sequence. Here we can see
Plane-3 is holding in the air -- it arrived at t=2 seconds, but both
gates were already occupied by Planes 1 and 2. Then Plane-4 arrives at
t=3 seconds and is also holding. At this point, two normal planes are
waiting in the air while both gates are occupied -- this is exactly the
congested state described in the assignment. Then Plane-5 with fuel
shortage arrives at t=4 seconds and joins the emergency queue. When a
gate becomes free, the ATC grants landing to Plane-5 with
EMERGENCY - PRIORITY, before Plane-3 and Plane-4 who arrived earlier.
This proves that emergency priority is working correctly.

Now I will scroll to the gate service output. Notice that at this point,
we can see a passenger thread, a supply thread, and a refuel thread all
printing at the same time. For example, Thread-Passenger, Thread-Supply,
and Thread-Refuel for the same plane appear together. This proves that
passenger disembarkation, supply restocking, and refuelling are running
concurrently, as required. We can also see that when two planes are at
different gates, they run their services in parallel as well.

For the refuelling truck, we can see that when one plane is being
refuelled, the next plane prints "waiting for fuel truck" and only
starts refuelling after the first one finishes. This proves the
ReentrantLock is working and only one plane uses the truck at a time.

At the end, the final statistics show that Gate-1 and Gate-2 are both empty, and
the sanity check says all gates empty is true. The program also prints that 6
planes were served, the number of passengers boarded, and the maximum, minimum,
and average waiting time.

In conclusion, this simulation meets the basic requirements and additional
requirements. It uses the concurrent programming concepts taught in the module:
`Thread`, `synchronized`, `wait()`, `notifyAll()`, `join()`, `ReentrantLock`,
`AtomicInteger`, and `volatile`. Thank you.

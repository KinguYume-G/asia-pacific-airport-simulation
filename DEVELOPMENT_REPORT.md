# Asia Pacific Airport Simulation -- Development Report
**CT074-3-2 Concurrent Programming | Individual Assignment**

---

## 1. Introduction and Background

### 1.1 System Overview

This system simulates the operations of Asia Pacific Airport. The airport has 1 runway,
2 gates, and 1 refuelling truck. Multiple airplane threads must share these limited
resources safely while maximising throughput.

The central concurrent challenge is coordinating multiple Airplane threads (each
running independently) through a single ATC thread that acts as the authoritative
scheduler -- without any thread acting on behalf of another.

### 1.2 Critical Design Principle -- "Objects Are Not Processes"

The assignment PDF explicitly flags this as a common Java error:

```
[Thread-Plane-1] ATC: Landing granted...   <-- WRONG: Plane thread speaks for ATC
```

Correct output (every ATC message printed by the ATC thread itself):
```
[Thread-Plane-1] Plane-1: Requesting Landing.
[Thread-ATC]     ATC: Landing Permission granted for Plane-1.
[Thread-ATC]     ATC: Gate-1 reserved for Plane-1.
[Thread-Plane-1] Plane-1: Landing on runway...
```

ATC extends Thread and runs its own scheduling loop. Airplane threads submit
request objects and block; ATC wakes, decides, and prints the result itself.

### 1.3 Assumptions

| # | Assumption |
|---|-----------|
| A1 | Airport has 2 Gates. The runway is not a gate. |
| A2 | Max 3 planes on the ground = runway (<=1) + Gate-1 (<=1) + Gate-2 (<=1) |
| A3 | Planes queue in the air; they do not occupy ground space while waiting |
| A4 | Gate must be pre-reserved before landing is granted (no ground waiting area) |
| A5 | Refuelling, supply/cleaning, and passenger disembark run concurrently at the gate |
| A6 | Each plane carries 1-50 passengers (random); each passenger is an independent thread |
| A7 | Emergency landing priority is implemented via an application-layer dual queue in ATC, NOT via Thread.setPriority() (which the JVM does not guarantee) |
| A8 | Congested scenario: Planes 1+2 occupy both gates; Planes 3+4 hold in air; Plane-5 (emergency) arrives after -- matching the assignment description exactly |
| A9 | Each step simulated with Thread.sleep(); total simulation under 60 seconds |
| A10 | Waiting time = time from landing request submitted to landing permission granted |
| A11 | groundCount is managed exclusively inside ATC's synchronized block; no separate Semaphore is used to avoid dual-management drift |

---

## 2. System Architecture

### 2.1 Class Overview

```
AirportSimulation (main)
|
+-- ATC extends Thread                  <- Real independent ATC thread
|   +-- emergencyQueue (LinkedList)     <- Priority queue for emergency planes
|   +-- landingQueue   (LinkedList)     <- Normal landing requests
|   +-- takeoffQueue   (LinkedList)     <- Takeoff requests
|   +-- runway: Runway                  <- Guarded by synchronized(this)
|   +-- gates: Gate[2]                  <- Guarded by synchronized(this)
|   +-- groundCount: int                <- Guarded by synchronized(this)
|
+-- LandingRequest                      <- Monitor object: Airplane waits, ATC notifies
+-- TakeoffRequest                      <- Monitor object: Airplane waits, ATC notifies
|
+-- Airplane extends Thread             <- 6 threads, one per plane, full lifecycle
|
+-- Gate                                <- Simple state object, mutated inside ATC lock
+-- Runway                              <- Simple state object, mutated inside ATC lock
|
+-- RefuellingTruck                     <- Shared resource, ReentrantLock controls access
+-- Passenger extends Thread            <- One thread per passenger for board/disembark
+-- Statistics                          <- AtomicInteger + synchronized recordWaitingTime
```

### 2.2 Thread Responsibility Model

| Thread | Implementation | Responsibility | Output prefix |
|--------|---------------|----------------|---------------|
| ATC | extends Thread | Receives requests, checks conditions, grants/denies, assigns gates | [Thread-ATC] |
| Airplane-N | extends Thread | Submits requests, waits for grants, executes flight steps | [Thread-Plane-N] |
| Passenger-N | extends Thread | Independent board/disembark action | [Thread-Passenger-PN-N] |
| Refuel/Supply | anonymous Thread | Gate service tasks spawned per aircraft | [Thread-Refuel/Supply-...] |

---

## 3. Core Concurrency Design

### 3.1 ATC as True Independent Thread

ATC extends Thread and holds a synchronized(this) loop. All airport state lives
inside one monitor. Airplane threads submit requests and block on the request
object; they never hold the ATC lock while waiting.

```java
// ATC.java -- main loop
@Override
public void run() {
    synchronized (this) {
        while (running) {
            boolean progressed = processAllRequests();
            if (!progressed) wait(1000); // releases lock; woken by notifyAll()
        }
    }
}

// Called by Airplane threads -- adds to queue and wakes ATC
public synchronized void submitLandingRequest(LandingRequest req) {
    if (req.getPlane().isEmergency()) emergencyQueue.add(req);
    else                              landingQueue.add(req);
    notifyAll();
}
```

All ATC decisions (grants, denials, gate assignments) are printed inside run(),
ensuring output always shows [Thread-ATC].

### 3.2 LandingRequest -- Airplane/ATC Communication Object

```java
// Airplane thread calls this -- blocks on LandingRequest's monitor (NOT ATC's)
public synchronized void waitForDecision() throws InterruptedException {
    while (!processed) wait();
}

// ATC thread calls this -- sets result, wakes Airplane
public synchronized void grant(Gate gate) {
    this.granted = true; this.assignedGate = gate; this.processed = true;
    notifyAll();
}
```

This design prevents any deadlock: the Airplane waits on its own request object,
so the ATC lock is always free for ATC to process other requests.

### 3.3 Gate Pre-reservation -- No Ground Waiting Area

ATC only grants landing when runway AND an available gate AND groundCount < 3
are ALL true simultaneously. The gate is set to occupied atomically inside the
same synchronized block before grant() is called.

```java
// ATC.java -- tryDispatchLanding()
boolean canLand = !runway.isOccupied() && gate != null && groundCount < 3;
if (canLand) {
    runway.setOccupied(true);   // reserve runway
    gate.setOccupied(true);     // pre-reserve gate BEFORE granting
    groundCount++;
    req.grant(gate);            // plane lands directly to this gate -- no waiting on ground
}
```

### 3.4 Emergency Priority via Dual Queue (Not Thread.setPriority)

```java
// ATC.java -- tryDispatchLanding()
// Emergency queue always drained first -- application-layer scheduling
LinkedList<LandingRequest> queue =
    !emergencyQueue.isEmpty() ? emergencyQueue : landingQueue;
```

Thread.setPriority() is NOT relied upon for correctness. The JVM does not
guarantee that a higher-priority thread executes first. The dual-queue approach
guarantees the scheduling order at the application level.

### 3.5 RefuellingTruck -- ReentrantLock (fair)

```java
// RefuellingTruck.java
private final ReentrantLock lock = new ReentrantLock(true); // fair=true, no starvation

public void refuel(String planeName) throws InterruptedException {
    lock.lockInterruptibly(); // interruptible -- no permanent block if interrupted
    try {
        Thread.sleep(2000); // simulate refuelling time
    } finally {
        lock.unlock();       // always released -- even on InterruptedException
    }
}
```

Each Airplane spawns a dedicated refuel thread that competes for this lock.
The fair queue ensures planes refuel in arrival order.

Lock ordering (deadlock prevention): Airplane threads acquire the ReentrantLock
only AFTER releasing the ATC lock (they are not inside any ATC synchronized
block when they call refuel()). ATC never calls refuel(). No circular wait.

### 3.6 Gate Services -- Three Concurrent Threads

```java
// Airplane.java -- performGateServices()
Thread supplyThread = new Thread(..., "Thread-Supply-Plane-N");  supplyThread.start();
Thread refuelThread = new Thread(..., "Thread-Refuel-Plane-N");  refuelThread.start();
// + disembarkThreads[] all started concurrently

for (Thread t : disembarkThreads) t.join(); // barrier: disembark before boarding
// boarding threads started...
for (Thread t : boardThreads)     t.join(); // barrier: board before requesting takeoff

supplyThread.join();
refuelThread.join();
```

### 3.7 Takeoff While Still Docked -- No Ground Holding Area

```java
// Airplane.java -- run()
// Step 6: request takeoff WHILE STILL DOCKED at gate
atc.submitTakeoffRequest(takeReq);
takeReq.waitForDecision();        // ATC reserves runway

// Step 7: THEN undock and release gate
atc.notifyGateVacated(gate, this); // gate freed here -- next plane can land

// Step 8: coast -> takeoff
```

By requesting runway before undocking, the plane transitions gate -> runway
in one motion. There is never a point where the plane is on the ground without
either a gate or the runway assigned to it.

### 3.8 Statistics -- AtomicInteger + synchronized

```java
// Statistics.java
public final AtomicInteger totalPlanesServed      = new AtomicInteger(0);
public final AtomicInteger totalPassengersBoarded = new AtomicInteger(0);

// Plain ArrayList + synchronized method (avoids Collections.synchronizedList)
private final List<Long> waitingTimes = new ArrayList<>();

public synchronized void recordWaitingTime(long ms) {
    waitingTimes.add(ms);
}
```

AtomicInteger uses CPU-level CAS (Compare-And-Swap) for lock-free increments.
Multiple Airplane threads call incrementAndGet() concurrently with no contention.

### 3.9 Arrival Modes

AirportSimulation has a RANDOM_MODE flag:
- false (default): fixed schedule {0,0,2000,1000,1000,1000} ms -- guarantees congested scenario
- true: rand.nextInt(3) * 1000 ms per spec ("every 0, 1, or 2 seconds")

All gaps in the fixed schedule are valid values from {0, 1, 2} seconds.

---

## 4. Airplane Lifecycle (8 Steps)

```
[Airplane Thread starts]
    |
    v
Step 1: Submit LandingRequest -> block on request.waitForDecision()
    |  [ATC wakes, checks: runway free + gate available + groundCount < 3]
    |  [ATC grants: reserves runway, pre-reserves gate, groundCount++]
    |  [ATC calls req.grant(gate) -> Airplane unblocked]
    v
Step 2: Land on runway (sleep 1000ms) -> notifyLandingComplete (runway freed)
    v
Step 3: Coast to Gate (sleep 800ms)
    v
Step 4: Gate Services [CONCURRENT]
    +-- Passenger DISEMBARK threads (1-50, each its own thread)
    +-- Supply/Cleaning thread
    +-- Refuel thread (waits for ReentrantLock on shared truck)
    v  [join() barrier: wait for disembark]
Step 5: Passenger BOARD threads (1-50) [join() barrier]
    v  [join() supply + refuel]
Step 6: Submit TakeoffRequest (while still docked) -> block until ATC grants runway
    v
Step 7: Undock -> notifyGateVacated (gate freed, next landing possible)
    v
Step 8: Coast to runway (sleep 800ms) -> Takeoff (sleep 1000ms)
        -> notifyTakeoffComplete (runway freed, groundCount--)
```

---

## 5. Congested Scenario

Assignment requirement: "2 planes waiting to land while 2 gates are occupied,
and a 3rd plane comes in with fuel shortage, requiring emergency landing."

Arrival schedule (all gaps from {0,1,2}s per spec):
- t=0s: Plane-1 -> lands, Gate-1 occupied
- t=0s: Plane-2 -> waits for runway, lands, Gate-2 occupied
- t=2s: Plane-3 -> both gates occupied, holds in air (1st in queue)
- t=3s: Plane-4 -> both gates occupied, holds in air (2nd in queue)
- t=4s: Plane-5 EMERGENCY -> 2 planes already waiting, both gates occupied
        -> enters emergencyQueue -> gets priority when gate frees
- t=5s: Plane-6 -> waits

Output evidence:
```
[Thread-ATC] ATC: Plane-3 - All gates occupied. Holding in air.
[Thread-ATC] ATC: Plane-5 [EMERGENCY] - All gates occupied. Holding in air.
...
[Thread-ATC] ATC: Landing Permission granted for Plane-5 [EMERGENCY - PRIORITY].
[Thread-ATC] ATC: Landing Permission granted for Plane-3.
[Thread-ATC] ATC: Plane-4 - Runway busy and all gates occupied. Holding in air.
```

---

## 6. Safety Analysis

### 6.1 Race Condition Prevention

| Shared Resource | Protection | Notes |
|----------------|-----------|-------|
| runway.isOccupied | synchronized(ATC) | All reads/writes inside one monitor |
| gate.isOccupied | synchronized(ATC) | Same ATC lock; atomic with groundCount |
| groundCount | synchronized(ATC) | Never modified outside ATC methods |
| totalPassengersBoarded | AtomicInteger (CAS) | Lock-free; no contention |
| waitingTimes | synchronized method | Explicit lock on Statistics object |

### 6.2 Deadlock Prevention (Coffman Conditions)

| Condition | Status | How prevented |
|-----------|--------|---------------|
| Mutual exclusion | Yes (necessary) | Cannot remove |
| Hold-and-wait | NO | Airplane holds only its request lock while waiting; never holds ATC lock |
| Non-preemption | Partial | lockInterruptibly() allows interruption |
| Circular wait | NO | Lock order is always ATC lock -> released -> ReentrantLock |

### 6.3 Starvation Prevention

- notifyAll() is used instead of notify() -- all waiting threads are woken
- Emergency priority is temporary; normal planes resume once emergency is served
- ReentrantLock(true) -- fair mode ensures planes refuel in order

### 6.4 Visibility

- volatile boolean running in ATC -- ensures the shutdown() signal is
  immediately visible across all threads (happens-before guarantee)
- All other shared state is inside synchronized(ATC) which provides the
  same happens-before guarantee through the monitor lock

---

## 7. Requirements Checklist

### Basic Requirements

| Requirement | Implementation | Status |
|------------|---------------|--------|
| 1 runway, mutual exclusion | ATC synchronized, runway.isOccupied | Done |
| Max 3 planes on ground | groundCount < 3 condition in ATC | Done |
| No ground waiting area | Gate pre-reserved before landing granted | Done |
| Full aircraft lifecycle | Airplane Thread 8-step run() | Done |
| Each step takes time | Thread.sleep() at each step | Done |
| Concurrent gate services | 3 thread groups + join() barriers | Done |
| 6 planes total | 6 Airplane threads in main | Done |
| Random 0/1/2s arrival (RANDOM_MODE=true) | rand.nextInt(3) * 1000 | Done |
| Max 50 passengers | rand.nextInt(50) + 1 | Done |
| Thread-prefixed output | [Thread.currentThread().getName()] | Done |
| ATC decisions from ATC thread | All log() calls inside ATC.run() | Done |
| Gate sanity check + statistics | Statistics.printFinalStats() | Done |

### Additional Requirements

| Requirement | Implementation | Status |
|------------|---------------|--------|
| 1 refuelling truck, mutual exclusion | ReentrantLock(fair) in RefuellingTruck | Done |
| Congested scenario | Controlled arrival schedule | Done |
| Emergency landing priority | Dual queue in ATC (emergencyQueue first) | Done |
| Emergency correctness via logic (not setPriority) | Application-layer queue, not JVM hint | Done |

---

## 8. Concurrency Concepts Summary

| Concept (Course Week) | Used In | Purpose |
|----------------------|---------|---------|
| extends Thread (Week 2) | ATC, Airplane, Passenger | Each entity runs in its own thread |
| synchronized method (Week 3) | ATC state methods, Statistics | Mutual exclusion on shared state |
| wait() / notifyAll() (Week 4) | ATC run loop, LandingRequest, TakeoffRequest | Condition waiting without busy-spin |
| ReentrantLock (Week 5-8) | RefuellingTruck | Single truck mutual exclusion |
| Semaphore -- NOT used | n/a | Replaced by groundCount inside ATC monitor |
| AtomicInteger (Week 10) | Statistics counters | Lock-free CAS increment |
| volatile (Week 10) | ATC.running | Visibility of shutdown signal |
| Thread.join() (Week 2) | Passenger barriers, main thread | Synchronisation points |
| Priority scheduling (Week 9) | ATC dual queue | Emergency planes served first |

---

*Report prepared for CT074-3-2 Individual Assignment*
*Asia Pacific University of Technology and Innovation*

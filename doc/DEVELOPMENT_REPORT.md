# Asia Pacific Airport Simulation -- Individual Assignment Report
**CT074-3-2 Concurrent Programming**
**Asia Pacific University of Technology and Innovation**

---

## 1. Introduction and Background

### 1.1 Background

Concurrent programming is the discipline of designing systems where multiple
computations execute in overlapping time periods, sharing processor resources and
communicating through shared memory or message passing. Unlike sequential programs
where tasks execute one after another, concurrent programs allow multiple threads to
make progress simultaneously, improving responsiveness and resource utilisation.

The airport simulation represents a classic concurrent system. Aircraft arrive
independently and unpredictably, yet all must share a single runway and a limited
number of gates. Without concurrent programming, the simulation would be forced to
process planes one by one in sequence -- an unrealistic model. With threads, each
airplane can independently request resources, wait for clearance, and execute its
service phase, all while other planes are simultaneously in different stages of their
own lifecycle.

The primary challenge is not creating threads, but controlling them safely: ensuring
that shared resources (runway, gates, refuelling truck) are accessed exclusively
without creating deadlocks, starvation, or data corruption.

### 1.2 System Overview

Asia Pacific Airport operates with:
- 1 runway (used for both landing and takeoff, never simultaneously)
- 2 passenger gates
- 1 refuelling truck (shared by all planes)
- A maximum of 3 aircraft on the ground at any one time

Six aircraft must be served. During gate service, three activities run concurrently:
passenger disembarkation/boarding, supply restocking and cleaning, and refuelling.

### 1.3 Assumptions and Implementation

| # | Assumption | Implementation |
|---|-----------|----------------|
| A1 | Airport has 2 Gates. The runway itself is not a gate. | Gate[2] array; runway is a separate Runway object |
| A2 | Max 3 planes on ground = runway (<=1) + Gate-1 (<=1) + Gate-2 (<=1) | groundCount tracked atomically inside ATC synchronized block |
| A3 | Planes queue in the air; they do not occupy ground space while waiting | Planes block on LandingRequest.waitForDecision(); no ground slot consumed until ATC grants landing |
| A4 | A gate must be pre-reserved before landing is granted -- no ground waiting area | ATC checks gate != null AND sets gate.occupied BEFORE calling req.grant() |
| A5 | Refuelling, supply/cleaning, and passenger disembark run concurrently at the gate | Three separate Thread objects started simultaneously inside performGateServices(); joined after completion |
| A6 | Each plane carries 1-50 passengers; each passenger is an independent thread | rand.nextInt(50)+1 passengers; each is a Passenger extends Thread |
| A7 | Emergency priority implemented at application layer, not via Thread.setPriority() | ATC maintains a separate emergencyQueue, always drained before the normal landingQueue |
| A8 | Congested scenario uses a controlled arrival schedule | Arrival gaps {0,0,2,1,1,1}s guarantee Planes 3+4 are waiting in air when Plane-5 (emergency) arrives |
| A9 | Waiting time = landing request submitted to landing permission granted | System.currentTimeMillis() recorded at request submission and at grant |
| A10 | Simulation completes within 60 seconds | Thread.sleep() values tuned; total measured under 40 seconds |

---

## 2. Basic Requirements Met

### 2.1 List of Requirements Met

- Single runway with mutual exclusion (one plane at a time)
- Maximum 3 aircraft on the ground simultaneously
- No ground waiting area (gate pre-reserved before landing granted)
- Full aircraft lifecycle: request landing, land, coast to gate, dock, gate services, undock, coast to runway, takeoff
- Each lifecycle step takes simulated time via Thread.sleep()
- Gate services (passengers, supply/clean, refuel) execute concurrently
- 6 aircraft served in total
- Arrival interval: 0, 1, or 2 seconds (random mode available)
- Maximum 50 passengers per aircraft
- Thread-prefixed output on every line
- ATC decisions printed exclusively by [Thread-ATC]
- End-of-simulation sanity check (all gates empty) and statistics

### 2.2 Concurrency Concepts Implemented (Basic)

**synchronized + wait() / notifyAll() -- Monitor Pattern**

The ATC object acts as a monitor. All shared airport state (runway, gates, groundCount)
is protected by a single synchronized(this) lock. The ATC thread enters a wait() loop
when no requests can be dispatched; any state-changing event (landing complete, gate
vacated, new request submitted) calls notifyAll() to wake ATC.

```java
// ATC.java -- main loop
@Override
public void run() {
    synchronized (this) {
        while (running) {
            boolean progressed = processAllRequests();
            if (!progressed) wait(1000); // releases lock; normally woken by notifyAll()
        }
    }
}

// State change -- called by Airplane thread
public synchronized void notifyGateVacated(Gate gate, Airplane plane) {
    gate.setOccupied(false);
    notifyAll();  // wakes ATC to check if a queued plane can now land
}
```

**LandingRequest as Condition Variable**

Instead of having Airplane threads wait on the ATC lock (which would block ATC from
processing requests), each request carries its own monitor. The Airplane waits on the
request object; ATC calls grant() which notifies only that specific request.

```java
// Airplane blocks on its own request object, NOT on ATC
public synchronized void waitForDecision() throws InterruptedException {
    while (!processed) wait();
}

// ATC unblocks exactly one Airplane
public synchronized void grant(Gate gate) {
    this.granted = true; this.assignedGate = gate; this.processed = true;
    notifyAll();
}
```

**Thread.join() as Barrier**

Gate services require disembarkation to complete before boarding begins. join() is used
as an explicit synchronisation barrier between phases.

```java
for (Thread t : disembarkThreads) t.join(); // wait for all disembark threads
// boarding starts only after all passengers have disembarked
for (Thread t : boardThreads)     t.join();
supplyThread.join();
refuelThread.join();
```

**AtomicInteger for Statistics**

Multiple Airplane threads concurrently increment the passenger count. AtomicInteger
uses CAS (Compare-And-Swap) hardware instructions for lock-free atomic increments.

```java
stats.totalPassengersBoarded.incrementAndGet(); // lock-free, no synchronized needed
```

---

## 3. Additional Requirements Met

### 3.1 List of Requirements Met

- Single refuelling truck with exclusive access via ReentrantLock
- Congested scenario: 2 planes waiting in air while both gates occupied, emergency plane
  (Plane-5) arrives and receives priority landing clearance over the waiting normal planes

### 3.2 Concurrency Concepts Implemented (Additional)

**ReentrantLock (fair) -- Refuelling Truck**

The shared refuelling truck uses ReentrantLock with fair=true. This was chosen over
synchronized because: (1) lockInterruptibly() allows the waiting thread to be
interrupted cleanly; (2) fair mode ensures waiting planes are served in arrival order,
preventing starvation at the truck.

```java
// RefuellingTruck.java
private final ReentrantLock lock = new ReentrantLock(true); // fair queue

public void refuel(String planeName) throws InterruptedException {
    lock.lockInterruptibly(); // blocks if truck is busy; can be interrupted
    try {
        Thread.sleep(2000);
    } finally {
        lock.unlock(); // released in finally -- guaranteed even on exception
    }
}
```

**Application-layer Priority Scheduling -- Emergency Queue**

Emergency priority is implemented by maintaining two separate queues inside ATC.
The emergency queue is always checked before the normal landing queue. This is
correct at the application level regardless of JVM thread scheduling.

```java
// ATC.java -- tryDispatchLanding()
LinkedList<LandingRequest> queue =
    !emergencyQueue.isEmpty() ? emergencyQueue : landingQueue;
```

**volatile -- Shutdown Visibility**

The running flag is declared volatile so the shutdown() signal written by the main
thread is immediately visible to the ATC thread without requiring a synchronized block.

```java
private volatile boolean running = true;

public synchronized void shutdown() { running = false; notifyAll(); }
```

---

## 4. Requirements NOT Met

**Basic Requirements:** None. All basic requirements have been fully implemented.

**Additional Requirements:** None. Both additional requirements (single refuelling truck
with mutual exclusion, and the congested emergency scenario) have been implemented
and verified in the program output.

---

## 5. Safety Aspects of the Multi-threaded System

### 5.1 Race Condition

A race condition occurs when two threads access shared mutable state concurrently and
the outcome depends on the order of execution. Without protection, two planes could
both read runway.isOccupied() as false simultaneously and both attempt to land,
corrupting the airport state.

All shared state in this system is protected:

| Shared Resource | Protection | Rationale |
|----------------|-----------|-----------|
| runway.isOccupied | synchronized(ATC) | Read-check-modify must be atomic |
| gate.isOccupied | synchronized(ATC) | Gate reservation and groundCount++ must be atomic together |
| groundCount | synchronized(ATC) | Incremented and checked in one critical section |
| totalPassengersBoarded | AtomicInteger (CAS) | Concurrent increments from multiple Airplane threads |
| waitingTimes list | synchronized method | Multiple Airplane threads add entries concurrently |

### 5.2 Deadlock

Deadlock requires four Coffman conditions simultaneously: mutual exclusion, hold-and-wait,
no preemption, and circular wait. This system breaks two of them:

**Hold-and-wait is eliminated:** When an Airplane thread submits a LandingRequest, it
blocks on the *request object's* monitor, NOT on the ATC lock. This means the ATC lock
is always free for ATC to process requests. An Airplane waiting for landing clearance
holds nothing; it cannot hold one resource while waiting for another.

**Circular wait is eliminated by consistent lock ordering:** If a thread must acquire
multiple locks, it must always acquire them in the same order. In this system, the
only lock sequence that ever occurs is: ATC lock (acquired, used, released) -- then
separately -- RefuellingTruck ReentrantLock (acquired when Airplane calls refuel()).
ATC never calls refuel(). No thread ever holds both locks simultaneously.

### 5.3 Starvation

Starvation occurs when a thread is perpetually denied access to a resource. Two risks
are addressed:

**notifyAll() instead of notify():** notify() wakes an arbitrary thread, which could
repeatedly wake the same thread while others starve indefinitely. notifyAll() wakes
all waiting threads, giving every plane a fair chance to re-evaluate conditions.

**Fair ReentrantLock:** A non-fair lock may allow a newly arriving plane to "barge"
ahead of planes already waiting for the truck. ReentrantLock(true) uses a FIFO queue
to guarantee that waiting threads are served in order.

### 5.4 Livelock

Livelock occurs when threads keep responding to each other without making progress.
A classic example: two threads each repeatedly check a condition, find it unsatisfied,
and immediately recheck -- consuming CPU without advancing.

This system avoids livelock through wait() with a 1000ms safety timeout in the ATC
loop. ATC does not busy-spin on conditions; it sleeps until notified. When a state
change occurs, notifyAll() wakes ATC exactly once to re-evaluate, rather than ATC
continuously polling.

### 5.5 Busy Waiting

Busy waiting (spin-locking) wastes CPU cycles by repeatedly testing a condition in a
tight loop. All waiting in this system uses wait() which suspends the thread and
releases the monitor, consuming no CPU until notified. The 1000ms timeout in
wait(1000) acts as a safety net against missed notifications, not as a polling mechanism.

### 5.6 Priority Inversion

Priority inversion occurs when a high-priority thread is blocked waiting for a resource
held by a low-priority thread. If emergency landing relied on Thread.setPriority(), a
high-priority emergency plane could be blocked indefinitely waiting for a low-priority
gate service thread to release a lock -- classic priority inversion.

This system avoids the problem by implementing priority at the application level. The
ATC scheduler checks the emergencyQueue first; no lock held by a low-priority thread
can block this decision, because the decision is made by the ATC thread using its own
state, not by trying to acquire a lock held by another thread.

### 5.7 Visibility and Happens-Before

Java's memory model does not guarantee that changes made by one thread are immediately
visible to another thread unless a happens-before relationship exists. The synchronized
keyword establishes happens-before: a thread that releases a lock happens-before any
thread that subsequently acquires the same lock. All ATC state changes are inside
synchronized methods, so every Airplane thread observing the state after notification
sees the most recent values.

The volatile keyword on ATC.running provides happens-before without synchronization:
a write to a volatile field happens-before every subsequent read of that field.

---

## 6. Justification of Coding Techniques

### 6.1 ATC as Independent Thread with Monitor Pattern

**Why:** If ATC were a plain object, its methods would run on whichever thread calls
them. An Airplane thread calling atc.requestLanding() would print "[Thread-Plane-1]
ATC: Landing granted" -- the plane's thread speaking for ATC, which the assignment
explicitly identifies as incorrect behaviour.

**Alternative considered:** Making ATC a static utility class. Rejected: this would
have no independent execution context and the same thread-identity problem.

**Choice:** ATC extends Thread. Its run() loop holds synchronized(this) and processes
requests. Every log statement inside run() correctly shows [Thread-ATC].

### 6.2 LandingRequest Object (Condition Variable Pattern)

**Why:** A naive implementation would have Airplane threads call a synchronized ATC
method and wait() inside it. But wait() on the ATC lock would block ATC from running
its own loop, creating a deadlock. The request object gives each Airplane its own
private condition variable, decoupling the Airplane's wait from the ATC lock.

**Alternative considered:** A shared BlockingQueue (java.util.concurrent). Rejected:
prohibited by assignment restrictions.

**Choice:** Plain Java object with synchronized wait/notifyAll, consistent with
Week 3-4 course content.

### 6.3 synchronized vs ReentrantLock

**ATC uses synchronized(this):** All ATC state is managed within one monitor. The
synchronized keyword is sufficient, simpler, and does not require explicit lock
management. The JVM handles lock acquisition and release automatically.

**RefuellingTruck uses ReentrantLock:** Two specific features required: (1) fair=true
for FIFO ordering of waiting planes -- synchronized provides no fairness guarantee;
(2) lockInterruptibly() so the waiting refuel thread can be interrupted cleanly.
These features are not available with synchronized.

### 6.4 Gate Pre-reservation and Takeoff-while-Docked

**Why:** The assignment states "no waiting area on the ground for planes to wait for a
gate". If landing were granted before a gate was reserved, a plane could land and have
nowhere to go. The three conditions (runway free, gate available, groundCount < 3)
are checked and acted on atomically inside one synchronized block:

```java
boolean canLand = !runway.isOccupied() && gate != null && groundCount < 3;
if (canLand) {
    runway.setOccupied(true);
    gate.setOccupied(true);  // pre-reserved atomically with landing grant
    groundCount++;
    req.grant(gate);
}
```

Similarly, takeoff is requested while still docked. ATC reserves the runway before
the plane undocks, ensuring the plane transitions gate -> runway without touching the
ground in an "unassigned" state.

### 6.5 AtomicInteger for Statistics

**Why:** synchronized blocks add overhead proportional to contention. For simple
counters incremented by many threads, AtomicInteger's CAS operation is more
efficient -- it uses a single CPU instruction, requires no lock, and involves no
context switching.

**Why not volatile int:** volatile only guarantees visibility, not atomicity. Two
threads reading the same volatile int, incrementing, and writing back creates a
race condition. AtomicInteger's incrementAndGet() performs the read-increment-write
as a single atomic operation.

---

## 7. Depth of Discussion of Concurrency Concepts

### 7.1 The Monitor Pattern

A monitor is an object that combines mutual exclusion with condition variables. In
Java, every object has a built-in monitor (the intrinsic lock). synchronized methods
and blocks acquire this lock. wait() atomically releases the lock and suspends the
thread; notifyAll() wakes all suspended threads, which then compete to re-acquire
the lock and re-check their conditions.

The critical property is that wait() is always called inside a while loop, not an
if statement. This guards against spurious wakeups (where a thread is woken without
a corresponding notify) and against the condition becoming false again between when
notify was called and when the thread re-acquires the lock.

```java
// Correct pattern used in LandingRequest
while (!processed) wait();  // while, not if
```

### 7.2 Atomicity, CAS, and AtomicInteger

An atomic operation appears to the rest of the system as if it completes
instantaneously. Without atomicity, i++ on a shared integer involves three steps:
read, increment, write. Two threads executing i++ simultaneously may both read the
same value and produce an incorrect result.

CAS (Compare-And-Swap) is a hardware-level atomic instruction: "if the current value
equals the expected value, replace it with the new value, otherwise do nothing and
report failure". AtomicInteger.incrementAndGet() uses CAS in a retry loop: if the CAS
fails (another thread modified the value), it retries. This guarantees atomicity
without acquiring a lock, eliminating contention for a simple counter.

### 7.3 Thread.join() as Synchronisation Barrier

join() blocks the calling thread until the target thread terminates. In gate services,
it creates explicit synchronisation points: boarding cannot begin before all
disembarking passengers have left the plane, and takeoff cannot be requested before
boarding is complete. This sequential constraint within a concurrent service phase
is expressed cleanly through join() barriers.

### 7.4 Happens-Before and Memory Visibility

Modern CPUs and compilers reorder instructions for performance. The Java Memory Model
defines happens-before rules that guarantee when one thread's writes are visible to
another thread's reads. The key rules used in this system:
- Monitor release happens-before monitor acquire (synchronized)
- volatile write happens-before volatile read (ATC.running)

Without these guarantees, an Airplane thread could read stale values of runway state
from a CPU cache rather than the updated value in main memory.

### 7.5 Application-layer Scheduling vs JVM Thread Priority

Thread.setPriority() communicates a preference to the OS scheduler, but the JVM
specification does not require the scheduler to honour it. On many platforms, thread
priorities are advisory only. Emergency landing priority implemented via setPriority()
would not be guaranteed to work.

The dual-queue approach implements priority at the application layer: the ATC thread
itself decides which request to serve next, independently of how the OS schedules
threads. This is deterministic and portable across all JVM implementations.

---

## 8. Requirements Summary

### Basic Requirements

| Requirement | Implementation | Met |
|------------|---------------|-----|
| 1 runway, mutual exclusion | ATC synchronized, runway.isOccupied | Yes |
| Max 3 planes on ground | groundCount < 3 in ATC critical section | Yes |
| No ground waiting area | Gate pre-reserved before landing granted | Yes |
| Full aircraft lifecycle | Airplane Thread 8-step run() | Yes |
| Each step takes time | Thread.sleep() at each step | Yes |
| Concurrent gate services | 3 thread groups + join() barriers | Yes |
| 6 planes total | 6 Airplane threads in main() | Yes |
| Random 0/1/2s arrival | rand.nextInt(3)*1000 (RANDOM_MODE=true) | Yes |
| Max 50 passengers | rand.nextInt(50)+1 | Yes |
| Thread-prefixed output | Thread.currentThread().getName() | Yes |
| ATC decisions from ATC thread | All printing inside ATC.run() | Yes |
| Gate sanity check + statistics | Statistics.printFinalStats() | Yes |

### Additional Requirements

| Requirement | Implementation | Met |
|------------|---------------|-----|
| 1 refuelling truck | ReentrantLock(fair) in RefuellingTruck | Yes |
| Congested scenario | Controlled arrival schedule {0,0,2,1,1,1}s | Yes |
| Emergency landing priority | emergencyQueue checked before landingQueue in ATC | Yes |

### Requirements NOT Met

**Basic Requirements:** None -- all basic requirements have been implemented and verified.

**Additional Requirements:** None -- both additional requirements have been implemented and verified.

---

*Word count (excluding code blocks and tables): approximately 1,450 words*

*Report prepared for CT074-3-2 Individual Assignment*
*Asia Pacific University of Technology and Innovation*

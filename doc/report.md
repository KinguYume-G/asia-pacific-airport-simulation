# Asia Pacific Airport Simulation Report

**Module:** CT074-3-2 Concurrent Programming  
**Assignment:** Individual Assignment - System

## 1. Introduction and Background

Concurrent programming is needed when several tasks make progress in the same
time period and must coordinate access to shared resources. The airport case is
a natural concurrent system: planes arrive independently, passengers board and
disembark at gates, cleaning and supply work can run during passenger movement,
and aircraft compete for one refuelling truck. A sequential solution would serve
one entire aircraft at a time, which is unrealistic and would not demonstrate the
shared-resource risks in the case study.

The implemented system simulates Asia Pacific Airport with one runway, two gates,
one refuelling truck, six aircraft, and passenger threads. The main challenge is
not just creating threads; it is controlling them safely so that planes never
collide on the runway, never wait on the ground without a gate, and never corrupt
statistics. Therefore, a real `ATC` thread is used as the central scheduler. Each
`Airplane` thread submits requests and waits, while all ATC decisions are printed
by `[Thread-ATC]`, avoiding the assignment's "objects are not processes" error.

### Assumptions

| Assumption | Implementation |
|---|---|
| Two gates and one runway | `Gate[2]` and one `Runway` object |
| Maximum three planes on ground | `groundCount < 3` inside ATC monitor |
| No ground waiting area | Gate pre-reserved before landing is granted |
| Plane-5 is emergency aircraft | Added to `emergencyQueue` |
| Each plane has 1-50 passengers | `rand.nextInt(50) + 1` |
| Controlled demo plus random mode | `RANDOM_MODE=false` guarantees scenario; `true` uses random 0/1/2s gaps |

## 2. Basic Requirements Met

All basic requirements are implemented. Six `Airplane` threads are created. Each
plane performs the complete lifecycle: request landing, land, coast to its gate,
dock, disembark passengers, receive supply/cleaning and refuelling service, board
new passengers, request takeoff while still docked, undock, coast to runway, and
take off. Each major action uses `Thread.sleep()` to simulate time.

The single runway is protected by `runway.isOccupied` inside ATC's synchronized
monitor. Ground capacity is controlled by `groundCount`, also inside the same
monitor. A plane receives landing permission only if the runway is free, a gate
exists, and ground capacity is available. ATC marks the gate occupied before
calling `req.grant(gate)`, so the plane lands directly to a reserved gate and
never waits on the ground.

Gate services are concurrent. Passenger disembark threads, a supply/cleaning
thread, and a refuel thread are started together. `join()` is used as a barrier:
boarding starts only after all disembark threads finish, and takeoff is requested
only after boarding, supply/cleaning, and refuelling have completed. At the end,
`Statistics.printFinalStats()` checks that both gates are empty and prints planes
served, passengers boarded, and maximum, minimum, and average waiting time.

```java
LinkedList<LandingRequest> queue =
    !emergencyQueue.isEmpty() ? emergencyQueue : landingQueue;
boolean canLand = !runway.isOccupied() && gate != null && groundCount < 3;
if (canLand) {
    runway.setOccupied(true);
    gate.setOccupied(true);
    groundCount++;
    req.grant(gate);
}
```

```java
for (Thread t : disembarkThreads) t.join();
for (Thread t : boardThreads) t.join();
supplyThread.join();
refuelThread.join();
```

## 3. Additional Requirements Met

The single refuelling truck is implemented as one shared `RefuellingTruck` object.
It uses a fair `ReentrantLock(true)`, so only one aircraft can refuel at a time
and waiting planes are served in order. `lockInterruptibly()` was chosen because
it allows a waiting refuel thread to be interrupted cleanly, while `finally`
guarantees unlock even if the simulated refuelling sleep is interrupted.

```java
private final ReentrantLock lock = new ReentrantLock(true);

public void refuel(String planeName) throws InterruptedException {
    lock.lockInterruptibly();
    try {
        Thread.sleep(2000);
    } finally {
        lock.unlock();
    }
}
```

The congested emergency scenario is implemented using a controlled arrival
schedule. Planes 1 and 2 occupy both gates. Plane-3 and Plane-4 then wait in the
air because both gates are occupied. Plane-5 arrives later with fuel shortage and
enters `emergencyQueue`. ATC always checks `emergencyQueue` before `landingQueue`,
so Plane-5 receives priority over the two normal waiting planes. This does not
depend on `Thread.setPriority()`, because JVM thread priority is only a scheduling
hint and cannot guarantee correctness.

## 4. Requirements Not Met

**Basic Requirements:** None. All basic requirements have been implemented and
verified in the running program.

**Additional Requirements:** None. The single refuelling truck and congested
emergency landing scenario have both been implemented and verified.

## 5. Safety Aspects of the Multi-threaded System

**Race condition:** A race condition would occur if two planes simultaneously
observed the runway as free and both landed. This is prevented because runway,
gate, queue, and `groundCount` state are modified only inside ATC's synchronized
monitor. Gate reservation and `groundCount++` happen in one critical section.

**Deadlock:** Deadlock requires the Coffman conditions: mutual exclusion,
hold-and-wait, no preemption, and circular wait. Mutual exclusion is necessary,
but hold-and-wait and circular wait are broken. An airplane waits on its own
`LandingRequest` or `TakeoffRequest`, not on the ATC lock, so ATC remains free
to process requests. ATC also never calls `RefuellingTruck.refuel()`, so no
thread holds the ATC lock while waiting for the truck lock.

**Starvation:** `notifyAll()` is used instead of `notify()` so every waiting
thread can re-check its condition. The emergency queue is temporary: after the
emergency plane is served, normal planes continue. The fair refuelling lock also
prevents a waiting plane from being repeatedly skipped.

**Livelock and busy waiting:** ATC does not spin in a loop repeatedly checking
conditions. If no request can be dispatched, it calls `wait(1000)`, releasing the
monitor and sleeping until `notifyAll()` or the safety timeout. This avoids busy
waiting and prevents threads from repeatedly reacting without progress.

**Priority inversion:** Emergency service is not based on JVM thread priority.
Instead, ATC uses application-layer scheduling: the emergency queue is checked
first by the ATC thread. This avoids relying on low-level scheduler behaviour.

**Visibility:** Java's monitor rules create happens-before relationships between
synchronized releases and later synchronized acquisitions. Thus updates to runway,
gate, queue, and ground state become visible to other threads. `volatile running`
ensures that the main thread's shutdown signal is visible to the ATC thread.

```java
public synchronized void waitForDecision() throws InterruptedException {
    while (!processed) wait();
}

public synchronized void grant(Gate gate) {
    assignedGate = gate;
    processed = true;
    notifyAll();
}
```

## 6. Justification of Coding Techniques and Concept Depth

`ATC extends Thread` was selected because ATC is a real actor, not a passive
object. If airplane threads directly printed ATC decisions, the output would show
the wrong thread identity. The monitor pattern (`synchronized`, `wait()`, and
`notifyAll()`) is appropriate for ATC because all airport control state belongs
to one scheduler and should be guarded by one simple monitor.

`LandingRequest` and `TakeoffRequest` are condition-variable objects. They let
airplanes block without holding the ATC lock. The `while (!processed) wait()`
pattern protects against spurious wakeups and re-checks the condition after the
thread wakes.

`ReentrantLock` is used only for the refuelling truck because that resource needs
fair ordering and interruptible lock acquisition. For ATC, `synchronized` is
simpler and safer because no extra lock features are required.

`AtomicInteger` is used for simple counters. A normal `int++` is read, increment,
and write, so two threads can overwrite each other. `AtomicInteger.incrementAndGet()`
uses CAS (Compare-And-Swap), making the increment atomic without a monitor. A
`volatile int` would not be enough because volatile gives visibility, not atomicity.

## 7. Testing and Output Evidence

The system compiles with `javac *.java` and completes in less than 60 seconds.
A controlled run prints:

```text
Mode: Controlled congested scenario
ATC: Plane-3 - Runway busy and all gates occupied. Holding in air.
ATC: Plane-5 [EMERGENCY] - All gates occupied. Holding in air.
ATC: Landing Permission granted for Plane-5 [EMERGENCY - PRIORITY].
Planes Served         : 6
Result : All gates empty = true
```

The output also reports passengers boarded and maximum, minimum, and average
waiting time. Restricted automatic concurrency facilities such as `ExecutorService`,
`ForkJoinPool`, `CompletableFuture`, `parallelStream`, `Timer`, and
`PriorityBlockingQueue` are not used.

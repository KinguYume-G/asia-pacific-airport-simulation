# Asia Pacific Airport Simulation Report

**Module:** CT074-3-2 Concurrent Programming  
**Assignment:** Individual Assignment - System

## 1. Introduction and Background

This system simulates the operation of Asia Pacific Airport using explicit Java
concurrent programming. The airport is intentionally small: it has one runway,
two gates, and one refuelling truck. These resources are shared by six airplane
threads, each of which must request landing permission, land, move to a gate,
complete servicing, board new passengers, request takeoff, and depart.

Concurrency is required because several activities can happen at the same time.
Different airplanes can be waiting, passengers can disembark or board at gates,
supply and cleaning can run while passengers move, and refuelling can be requested
by more than one aircraft. However, the runway, gates, airport ground capacity,
and refuelling truck must be protected to avoid race conditions, collisions, and
incorrect statistics. Therefore, the main design uses a real ATC thread as the
central scheduler, while airplane and passenger activities remain independent
threads.

## 2. Assumptions

The airport has exactly two gates and one runway. The runway is not counted as a
gate. At most three planes can be on airport ground at once, including a plane on
the runway. Since the airport has no ground waiting area, a gate must be reserved
before landing permission is granted. A plane that cannot get a runway and gate
waits in the air.

`RANDOM_MODE` is available in `AirportSimulation.java`. When it is `true`, planes
arrive using random 0, 1, or 2 second gaps. For the submission demonstration, the
default is `false`, using a controlled schedule whose gaps are still valid values
from 0, 1, or 2 seconds. This guarantees the required congested scenario. Plane-5
is the emergency aircraft with fuel shortage. Each plane carries between 1 and 50
passengers, and every passenger is represented as an independent thread. No basic
or additional requirement is intentionally left unmet.

## 3. Basic Requirements Met

Six `Airplane` threads are created in `AirportSimulation`. The full lifecycle is
implemented in `Airplane.run()`: request landing, land on runway, coast to the
assigned gate, dock, disembark passengers, refill supplies and clean, refuel,
board passengers, request takeoff, undock, coast to runway, and take off. Each
major step uses `Thread.sleep()` to simulate elapsed time.

The single runway is protected by the ATC monitor using `runway.isOccupied`.
Ground capacity is protected by `groundCount < 3`, also inside the ATC monitor.
Gate safety is handled by pre-reservation: ATC grants landing only when a gate is
available, and marks the gate occupied before waking the airplane. This prevents
any aircraft from landing and then waiting on the ground for a gate.

Gate services are concurrent. Passenger disembark threads, a supply/cleaning
thread, and a refuelling thread are started together. `join()` is then used as a
barrier so disembarking completes before boarding, and all service threads finish
before the plane requests takeoff. At the end, `Statistics.printFinalStats()`
checks that both gates are empty and prints planes served, passengers boarded,
and maximum, minimum, and average waiting time.

## 4. Additional Requirements Met

The single refuelling truck is represented by `RefuellingTruck`. It uses a fair
`ReentrantLock(true)`, so only one aircraft can be refuelled at a time and waiting
planes are served in lock order. The lock is acquired with `lockInterruptibly()`
and always released in a `finally` block.

The congested scenario is reproduced by the controlled arrival schedule. Planes
1 and 2 occupy both gates. Plane-3 and Plane-4 then request landing while the
gates are occupied, so they wait in the air. Plane-5 arrives later as the
emergency aircraft. It enters ATC's `emergencyQueue`, which is always processed
before the normal landing queue. Therefore, when a gate becomes available, Plane-5
receives landing permission before Plane-3 and Plane-4. This priority is not
implemented using `Thread.setPriority()`, because Java does not guarantee that
higher priority threads run first.

## 5. Safety Aspects of the Multi-threaded System

Race conditions are prevented by keeping all airport state changes inside the
ATC monitor. The runway state, gate state, landing queues, takeoff queue, and
`groundCount` are read or written only through synchronized ATC methods or inside
ATC's synchronized run loop. This means that only one thread can modify the
airport control state at a time.

Deadlock is avoided by separating request waiting from ATC state locking. An
airplane submits a `LandingRequest` or `TakeoffRequest`, then waits on that request
object, not on the ATC object. Therefore, a waiting airplane does not hold the ATC
lock. The refuelling lock is also never acquired inside ATC's synchronized block.
The order is: use ATC monitor for scheduling, release it, then an airplane service
thread may compete for the refuelling truck. This prevents circular wait.

Starvation is reduced using `notifyAll()` instead of `notify()`, so all waiting
threads can recheck their conditions. The emergency queue gives temporary priority
only while an emergency request exists; after the emergency aircraft is served,
normal planes continue. The fair `ReentrantLock(true)` prevents a plane from being
repeatedly skipped while waiting for refuelling.

Visibility is handled by Java monitor rules. Entering and leaving synchronized
blocks creates the required happens-before relationship for runway, gate, queue,
and ground-count updates. The ATC shutdown flag `running` is declared `volatile`,
so the main thread's shutdown signal is visible to the ATC thread. Thread identity
is also safe: all ATC decisions are printed by `[Thread-ATC]`, so no airplane
thread speaks on behalf of ATC.

## 6. Justification of Coding Techniques

`ATC extends Thread` because ATC is an actor in the simulation, not just a passive
object. This directly addresses the assignment warning that objects are not
processes. Airplanes only submit request objects and wait for decisions.

`LandingRequest` and `TakeoffRequest` implement the taught `wait()` / `notifyAll()`
pattern. They act as communication objects between airplane threads and the ATC
thread. This keeps blocking logic simple and prevents airplanes from holding the
ATC lock while waiting.

`ReentrantLock(true)` is used for the refuelling truck because it clearly models
one shared service resource with exclusive access. `AtomicInteger` is used for
`totalPlanesServed` and `totalPassengersBoarded`; its CAS-based increments are
safe when multiple airplane threads update statistics. Waiting times use a plain
`ArrayList` protected by a synchronized `recordWaitingTime()` method, avoiding
automatic concurrency collections outside the taught style.

`Thread.join()` is used as a synchronization barrier. Airplane service cannot move
to boarding until all disembark threads finish, and the plane cannot request
takeoff until boarding, supply/cleaning, and refuelling are complete. Restricted
automatic concurrency tools such as `ExecutorService`, `ForkJoinPool`,
`CompletableFuture`, `parallelStream`, `Timer`, and `PriorityBlockingQueue` are
not used.

## 7. Key Code Snippets

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
public synchronized void waitForDecision() throws InterruptedException {
    while (!processed) wait();
}

public synchronized void grant(Gate gate) {
    assignedGate = gate;
    processed = true;
    notifyAll();
}
```

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

```java
public final AtomicInteger totalPlanesServed = new AtomicInteger(0);
private final List<Long> waitingTimes = new ArrayList<>();

public synchronized void recordWaitingTime(long ms) {
    waitingTimes.add(ms);
}
```

```java
for (Thread t : disembarkThreads) t.join();
for (Thread t : boardThreads) t.join();
supplyThread.join();
refuelThread.join();
```

## 8. Testing and Output Evidence

The system compiles successfully with:

```text
javac *.java
```

The simulation completes in less than 60 seconds. A successful controlled run
prints:

```text
Mode: Controlled congested scenario
ATC: Plane-3 - Runway busy and all gates occupied. Holding in air.
ATC: Plane-5 [EMERGENCY] - All gates occupied. Holding in air.
ATC: Landing Permission granted for Plane-5 [EMERGENCY - PRIORITY].
Planes Served         : 6
Result : All gates empty = true
```

The final statistics also show passengers boarded and the maximum, minimum, and
average waiting time for planes. This confirms that all planes completed their
full lifecycle, all gates were cleared, and the ATC sanity check passed.

## 9. Requirements Not Met

There are no known unmet basic or additional requirements in the implemented
system. The final submission still requires the separate presentation video and
the complete Java source folder in the required zip format.

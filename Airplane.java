import java.util.Random;

/**
 * An airplane executing the full gate-service lifecycle as a thread.
 *
 * Thread identity rule:
 *   Thread.getName() is final -- we CANNOT override it.
 *   getPlaneName() returns the human-readable label used in log messages.
 *   getName() (inherited) returns "Thread-Plane-N" and appears in [brackets].
 *
 * Lifecycle (8 steps):
 *   1. Submit LandingRequest  ->  block on request until ATC grants
 *   2. Land on runway         ->  notifyLandingComplete (runway freed)
 *   3. Coast to gate
 *   4. Gate services (concurrent): disembark || supply+clean || refuel
 *   5. Board new passengers
 *   6. Submit TakeoffRequest while STILL DOCKED  ->  block until ATC grants
 *      (ATC reserves runway before plane undocks -- no ground waiting area)
 *   7. Undock  ->  notifyGateVacated (gate freed)
 *   8. Coast to runway  ->  Takeoff  ->  notifyTakeoffComplete
 *
 * Concurrency demonstrated:
 *   - extends Thread (Week 2)
 *   - wait() via LandingRequest / TakeoffRequest (Week 4)
 *   - Three concurrent inner threads per gate (Week 2-3)
 *   - join() as barrier before next gate phase (Week 2)
 *   - AtomicInteger for statistics (Week 10)
 *   - ReentrantLock via RefuellingTruck (Week 5-8)
 */
public class Airplane extends Thread {

    private final int            planeId;
    private final boolean        emergency;
    private final ATC            atc;
    private final RefuellingTruck truck;
    private final Statistics     stats;
    private final Random         rand = new Random();

    public Airplane(int planeId, boolean emergency,
                    ATC atc, RefuellingTruck truck, Statistics stats) {
        super("Thread-Plane-" + planeId);
        this.planeId   = planeId;
        this.emergency = emergency;
        this.atc       = atc;
        this.truck     = truck;
        this.stats      = stats;
    }

    /** Human-readable plane label used in log messages. */
    public String  getPlaneName() { return "Plane-" + planeId; }
    public boolean isEmergency()  { return emergency; }

    // =======================================================================
    //  Main lifecycle
    // =======================================================================

    @Override
    public void run() {
        try {
            String label = getPlaneName() + (emergency ? " [EMERGENCY]" : "");

            // Step 1: Request landing
            log(label + ": Requesting Landing.");
            long requestTime = System.currentTimeMillis();

            LandingRequest landReq = new LandingRequest(this);
            atc.submitLandingRequest(landReq);
            landReq.waitForDecision();   // blocks until ATC grants

            long waitMs = System.currentTimeMillis() - requestTime;
            stats.recordWaitingTime(waitMs);

            Gate gate = landReq.getAssignedGate();

            // Step 2: Land
            log(getPlaneName() + ": Landing on runway...");
            Thread.sleep(1000);
            log(getPlaneName() + ": Landed successfully.");
            atc.notifyLandingComplete(this);   // frees runway

            // Step 3: Coast to gate
            log(getPlaneName() + ": Coasting to Gate-" + gate.getGateId() + "...");
            Thread.sleep(800);
            log(getPlaneName() + ": Docked at Gate-" + gate.getGateId() + ".");

            // Step 4 + 5: Gate services then boarding
            performGateServices(gate);

            // Step 6: Request takeoff WHILE STILL DOCKED
            // Gate is still occupied here -- ATC reserves runway first,
            // then plane undocks. This guarantees no ground holding area.
            log(getPlaneName() + ": Requesting Takeoff.");
            TakeoffRequest takeReq = new TakeoffRequest(this);
            atc.submitTakeoffRequest(takeReq);
            takeReq.waitForDecision();   // blocks until ATC assigns runway

            // Step 7: Undock -> vacate gate
            log(getPlaneName() + ": Undocking from Gate-" + gate.getGateId() + ".");
            Thread.sleep(500);
            atc.notifyGateVacated(gate, this);   // gate freed -- next plane can land

            // Step 8: Coast to runway -> Takeoff
            log(getPlaneName() + ": Coasting to runway for takeoff...");
            Thread.sleep(800);
            log(getPlaneName() + ": Taking off...");
            Thread.sleep(1000);
            log(getPlaneName() + ": Departed! Goodbye.");
            atc.notifyTakeoffComplete(this);   // runway freed + groundCount--

            stats.totalPlanesServed.incrementAndGet();   // AtomicInteger CAS

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log(getPlaneName() + ": Interrupted during operations.");
        }
    }

    // =======================================================================
    //  Gate services -- three concurrent threads + boarding barrier
    // =======================================================================

    /**
     * Starts three concurrent service threads:
     *   1) Passenger DISEMBARK threads (each passenger is its own thread)
     *   2) Supply and Cleaning thread
     *   3) Refuelling thread (acquires the shared RefuellingTruck lock)
     *
     * join() acts as a barrier: disembark must complete before boarding starts.
     * Boarding then proceeds; supply and refuel may still run in parallel.
     * All three complete before the plane requests takeoff.
     */
    private void performGateServices(Gate gate) throws InterruptedException {

        int disembarkCount = rand.nextInt(50) + 1;   // 1-50 passengers per assignment spec

        // 1) Start disembark threads
        Thread[] disembarkThreads = new Thread[disembarkCount];
        for (int i = 0; i < disembarkCount; i++) {
            disembarkThreads[i] = new Passenger(i + 1, planeId, Passenger.Action.DISEMBARK);
            disembarkThreads[i].start();
        }

        // 2) Supply and cleaning thread (concurrent with disembark)
        Thread supplyThread = new Thread(() -> {
            try {
                System.out.println("[" + Thread.currentThread().getName() + "] "
                    + getPlaneName() + ": Restocking supplies and cleaning aircraft...");
                Thread.sleep(2500);
                System.out.println("[" + Thread.currentThread().getName() + "] "
                    + getPlaneName() + ": Supplies restocked. Aircraft cleaned.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "Thread-Supply-" + getPlaneName());
        supplyThread.start();

        // 3) Refuel thread (concurrent -- competes for shared truck ReentrantLock)
        Thread refuelThread = new Thread(() -> {
            try {
                truck.refuel(getPlaneName());   // blocks on ReentrantLock if truck is busy
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "Thread-Refuel-" + getPlaneName());
        refuelThread.start();

        // Barrier: wait for ALL disembark threads before boarding
        for (Thread t : disembarkThreads) t.join();
        log(getPlaneName() + ": All passengers disembarked (" + disembarkCount + ").");

        // 4) Board new passengers
        int boardCount = rand.nextInt(50) + 1;
        Thread[] boardThreads = new Thread[boardCount];
        for (int i = 0; i < boardCount; i++) {
            boardThreads[i] = new Passenger(i + 1, planeId, Passenger.Action.BOARD);
            boardThreads[i].start();
            stats.totalPassengersBoarded.incrementAndGet(); // lock-free AtomicInteger
        }
        for (Thread t : boardThreads) t.join();
        log(getPlaneName() + ": All passengers boarded (" + boardCount + ").");

        // Barrier: wait for supply and refuel to finish
        supplyThread.join();
        refuelThread.join();
        log(getPlaneName() + ": Gate-" + gate.getGateId() + " services complete. Ready for departure.");
    }

    /** Prefixes every log line with this thread's name. */
    private void log(String msg) {
        System.out.println("[" + getName() + "] " + msg);
    }
}

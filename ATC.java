import java.util.LinkedList;

/**
 * Air Traffic Controller -- the central scheduler of the airport.
 *
 * Why ATC must be a real Thread:
 *   The assignment PDF forbids "objects acting for processes".
 *   If Airplane threads called atc.requestLanding() and printed
 *   "[Thread-Plane-1] ATC: Landing granted", the ATC message would be output
 *   by the Plane thread -- a classic error in Java concurrency.
 *
 *   Here ATC extends Thread and runs its OWN loop. Every ATC decision is
 *   printed inside run(), so output always shows [Thread-ATC].
 *
 * Concurrency mechanisms:
 *
 *   1. synchronized(this) + wait() / notifyAll()  [Week 3-4]
 *      The entire ATC state (runway, gates, groundCount, queues) lives inside
 *      ONE monitor (the ATC object). All reads/writes are inside synchronized
 *      blocks. ATC waits when there is nothing to process; any state change
 *      by an Airplane calls notifyAll() to wake ATC immediately.
 *
 *   2. Dual request queues  [Week 9 -- priority inversion / scheduling]
 *      emergencyQueue is always drained before landingQueue.
 *      This is APPLICATION-LAYER scheduling -- does NOT rely on
 *      Thread.setPriority() which the JVM does not guarantee.
 *
 *   3. Gate pre-reservation  [requirement: no ground waiting area]
 *      ATC only grants a landing if runway AND a gate AND ground capacity
 *      are ALL simultaneously available. The gate is marked occupied atomically
 *      with the landing grant, so the plane can taxi straight in.
 *
 *   4. Takeoff requested while still docked  [no extra ground waiting slot]
 *      Runway is reserved by ATC before the plane undocks, so the plane
 *      moves from gate -> runway without ever needing a ground holding area.
 *
 * Lock ordering (deadlock prevention):
 *   ATC lock -> then LandingRequest / TakeoffRequest lock (inside grant())
 *   Airplane threads hold LandingRequest/TakeoffRequest lock (in wait()),
 *   never the ATC lock simultaneously.
 *   RefuellingTruck lock is acquired by Airplane threads ONLY, never inside
 *   ATC's synchronized block.  -> No circular wait possible.
 */
public class ATC extends Thread {

    // Request queues
    private final LinkedList<LandingRequest>  emergencyQueue = new LinkedList<>();
    private final LinkedList<LandingRequest>  landingQueue   = new LinkedList<>();
    private final LinkedList<TakeoffRequest>  takeoffQueue   = new LinkedList<>();

    // Shared airport state (guarded by synchronized(this))
    private final Runway runway;
    private final Gate[] gates;
    private int          groundCount = 0;   // planes currently on the ground

    // Lifecycle flag
    private volatile boolean running = true;

    public ATC(Runway runway, Gate[] gates) {
        super("Thread-ATC");
        this.runway = runway;
        this.gates  = gates;
    }

    // =======================================================================
    //  Methods called by Airplane threads  (all synchronized on ATC monitor)
    // =======================================================================

    /** Airplane submits landing request then blocks on the request object. */
    public synchronized void submitLandingRequest(LandingRequest req) {
        if (req.getPlane().isEmergency()) {
            emergencyQueue.add(req);
        } else {
            landingQueue.add(req);
        }
        notifyAll(); // wake ATC thread to process the new request
    }

    /** Airplane submits takeoff request then blocks on the request object. */
    public synchronized void submitTakeoffRequest(TakeoffRequest req) {
        takeoffQueue.add(req);
        notifyAll();
    }

    /** Called by Airplane after it has physically landed -- frees the runway. */
    public synchronized void notifyLandingComplete(Airplane plane) {
        runway.setOccupied(false);
        log("ATC: Runway released after landing of " + plane.getPlaneName() + ".");
        notifyAll(); // a runway-free event may unblock a takeoff request
    }

    /**
     * Called by Airplane when it undocks and vacates the gate.
     * At this point the gate is free for the next incoming plane.
     */
    public synchronized void notifyGateVacated(Gate gate, Airplane plane) {
        gate.setOccupied(false);
        log("ATC: Gate-" + gate.getGateId() + " vacated by " + plane.getPlaneName() + ".");
        notifyAll(); // a free gate may unblock a queued landing request
    }

    /**
     * Called by Airplane after it has taken off.
     * Frees the runway AND decrements the ground count.
     */
    public synchronized void notifyTakeoffComplete(Airplane plane) {
        runway.setOccupied(false);
        groundCount--;
        log("ATC: " + plane.getPlaneName() + " has departed. "
            + "Ground count: " + groundCount + "/3.");
        notifyAll();
    }

    /** Signals the ATC thread to exit its loop after all planes have gone. */
    public synchronized void shutdown() {
        running = false;
        notifyAll(); // wake ATC in case it is waiting
    }

    // =======================================================================
    //  ATC main loop
    // =======================================================================

    @Override
    public void run() {
        log("ATC: Online. Airport is open for operations.");

        synchronized (this) {               // hold ATC monitor for the whole loop
            while (running) {
                try {
                    boolean progressed = processAllRequests();

                    if (!progressed) {
                        // Nothing could be dispatched right now.
                        // wait() releases the monitor so Airplane threads can
                        // call the notify methods above.
                        // 1000 ms timeout is a safety net; normally woken by notifyAll().
                        wait(1000);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log("ATC: Offline. All aircraft have departed. Closing airport.");
    }

    // =======================================================================
    //  Private scheduling logic  (always called within synchronized(this))
    // =======================================================================

    /**
     * Tries to dispatch one landing and one takeoff from the queues.
     * Returns true if at least one request was successfully dispatched
     * (used to decide whether to wait or loop immediately).
     */
    private boolean processAllRequests() {
        boolean progressed = false;

        progressed |= tryDispatchLanding();
        progressed |= tryDispatchTakeoff();

        return progressed;
    }

    /**
     * Dispatches the highest-priority landing request if all three conditions
     * are met simultaneously:
     *   1) runway is free
     *   2) a gate is available (pre-reserved for the plane)
     *   3) ground count < 3
     *
     * Emergency queue is always checked first (Week 9 -- application-layer
     * priority scheduling, not JVM thread priority).
     */
    private boolean tryDispatchLanding() {
        // Pick the correct queue: emergency first
        LinkedList<LandingRequest> queue =
            !emergencyQueue.isEmpty() ? emergencyQueue : landingQueue;

        if (queue.isEmpty()) return false;

        LandingRequest req  = queue.peek();
        Gate           gate = findAvailableGate();

        boolean canLand = !runway.isOccupied() && gate != null && groundCount < 3;

        if (canLand) {
            queue.poll();                  // remove from queue
            runway.setOccupied(true);      // reserve runway
            gate.setOccupied(true);        // pre-reserve gate -- plane lands directly into it
            groundCount++;

            log("ATC: Landing Permission granted for " + req.getPlane().getPlaneName()
                + (req.getPlane().isEmergency() ? " [EMERGENCY - PRIORITY]" : "") + ".");
            log("ATC: Gate-" + gate.getGateId()
                + " reserved for " + req.getPlane().getPlaneName() + ".");

            req.grant(gate);  // unblocks the Airplane thread
            return true;

        } else {
            // Print "holding" message only once so we don't spam the console
            if (!req.isHoldingPrinted()) {
                log("ATC: " + req.getPlane().getPlaneName()
                    + (req.getPlane().isEmergency() ? " [EMERGENCY]" : "")
                    + " - " + holdReason(gate) + ". Holding in air.");
                req.setHoldingPrinted();
            }
            return false;
        }
    }

    /**
     * Grants takeoff clearance for the first plane in the takeoff queue
     * when the runway is free.
     */
    private boolean tryDispatchTakeoff() {
        if (takeoffQueue.isEmpty()) return false;

        TakeoffRequest req = takeoffQueue.peek();

        if (!runway.isOccupied()) {
            takeoffQueue.poll();
            runway.setOccupied(true);  // runway reserved until notifyTakeoffComplete

            log("ATC: Takeoff clearance granted for "
                + req.getPlane().getPlaneName() + ". Runway assigned.");

            req.grant(); // unblocks the Airplane thread
            return true;
        }
        return false;
    }

    // =======================================================================
    //  Helpers
    // =======================================================================

    private Gate findAvailableGate() {
        for (Gate g : gates) {
            if (!g.isOccupied()) return g;
        }
        return null;
    }

    private String holdReason(Gate gate) {
        if (runway.isOccupied() && gate == null && groundCount >= 3)
            return "Runway busy, all gates occupied, airport at capacity";
        if (runway.isOccupied() && gate == null)
            return "Runway busy and all gates occupied";
        if (runway.isOccupied())
            return "Runway busy";
        if (gate == null)
            return "All gates occupied";
        if (groundCount >= 3)
            return "Airport at full capacity (3/3)";
        return "Waiting";
    }

    /** Convenience logger that always shows the ATC thread name. */
    private void log(String msg) {
        System.out.println("[" + getName() + "] " + msg);
    }
}

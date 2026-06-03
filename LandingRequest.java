/**
 * Communication object between an Airplane thread and the ATC thread.
 *
 * Pattern:
 *   Airplane calls waitForDecision() -> blocks on THIS object's monitor.
 *   ATC       calls grant()          -> sets result, notifyAll() on THIS object.
 *
 * This keeps the Airplane waiting on LandingRequest's lock, NOT on ATC's lock,
 * so the ATC thread is never blocked by a waiting Airplane.
 */
public class LandingRequest {

    private final Airplane plane;
    private boolean        processed     = false;
    private boolean        granted       = false;
    private Gate           assignedGate  = null;

    /** Printed once by ATC when conditions are not yet met -- avoids log spam. */
    private boolean holdingPrinted = false;

    public LandingRequest(Airplane plane) {
        this.plane = plane;
    }

    // Called by Airplane thread

    /** Blocks the calling Airplane thread until ATC makes a decision. */
    public synchronized void waitForDecision() throws InterruptedException {
        while (!processed) {
            wait();
        }
    }

    // Called by ATC thread

    /** ATC grants landing and pre-assigns a gate. */
    public synchronized void grant(Gate gate) {
        this.granted      = true;
        this.assignedGate = gate;
        this.processed    = true;
        notifyAll();   // wake the waiting Airplane thread
    }

    // Getters

    public Airplane getPlane()          { return plane; }
    public boolean  isGranted()         { return granted; }
    public Gate     getAssignedGate()   { return assignedGate; }
    public boolean  isHoldingPrinted()  { return holdingPrinted; }
    public void     setHoldingPrinted() { this.holdingPrinted = true; }
}

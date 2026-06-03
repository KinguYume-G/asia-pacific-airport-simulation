/**
 * Communication object between an Airplane thread and the ATC thread for takeoff.
 * Same monitor pattern as LandingRequest.
 */
public class TakeoffRequest {

    private final Airplane plane;
    private boolean        processed = false;
    private boolean        granted   = false;

    public TakeoffRequest(Airplane plane) {
        this.plane = plane;
    }

    // Called by Airplane thread

    /** Blocks until ATC grants takeoff clearance. */
    public synchronized void waitForDecision() throws InterruptedException {
        while (!processed) {
            wait();
        }
    }

    // Called by ATC thread

    public synchronized void grant() {
        this.granted   = true;
        this.processed = true;
        notifyAll();
    }

    // Getters

    public Airplane getPlane()  { return plane; }
    public boolean  isGranted() { return granted; }
}

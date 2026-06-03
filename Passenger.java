import java.util.Random;

/**
 * A single passenger performing one boarding or disembarking action.
 *
 * Concurrency: each Passenger IS a thread (extends Thread, Week 2).
 * Many Passenger threads run concurrently per gate, simulating the real
 * scenario where an entire plane-load boards/disembarks in parallel.
 *
 * The Airplane thread starts all Passenger threads then join()s them,
 * acting as a barrier before the next gate phase begins.
 */
public class Passenger extends Thread {

    public enum Action { BOARD, DISEMBARK }

    private final int    passengerId;
    private final int    planeId;
    private final Action action;
    private final Random rand = new Random();

    public Passenger(int passengerId, int planeId, Action action) {
        // Thread name follows the required output format
        super("Thread-Passenger-P" + planeId + "-" + passengerId);
        this.passengerId = passengerId;
        this.planeId     = planeId;
        this.action      = action;
    }

    @Override
    public void run() {
        try {
            // Each passenger takes a random amount of time (realistic)
            Thread.sleep(rand.nextInt(300) + 100);

            if (action == Action.DISEMBARK) {
                System.out.println("[" + getName() + "] "
                    + "Passenger-" + passengerId
                    + ": Disembarking from Plane-" + planeId + ".");
            } else {
                System.out.println("[" + getName() + "] "
                    + "Passenger-" + passengerId
                    + ": Boarding Plane-" + planeId + ".");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

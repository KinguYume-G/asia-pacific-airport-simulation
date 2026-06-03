import java.util.concurrent.locks.ReentrantLock;

/**
 * The single shared refuelling truck.
 *
 * Concurrency mechanism: ReentrantLock (Week 5-8).
 *   - Only ONE airplane can be refuelled at a time.
 *   - lockInterruptibly() lets the JVM interrupt a waiting airplane thread
 *     cleanly instead of blocking it forever.
 *   - Unlock is always in a finally block to guarantee release even if
 *     Thread.sleep() is interrupted.
 *   - fair=true ensures planes queue in arrival order (no starvation).
 *
 * The truck is a shared resource object, not a thread itself.
 * Each Airplane spawns its own "refuel thread" that calls refuel() here,
 * competing for the single lock.
 */
public class RefuellingTruck {

    private final ReentrantLock lock = new ReentrantLock(true); // fair queue

    /**
     * Refuels the named plane. Blocks until the truck is available.
     * Called from a dedicated refuel thread inside Airplane.
     */
    public void refuel(String planeName) throws InterruptedException {
        System.out.println("[" + Thread.currentThread().getName() + "] "
            + "RefuellingTruck: " + planeName + " waiting for fuel truck...");

        lock.lockInterruptibly(); // acquire -- blocks if another plane is being refuelled
        try {
            System.out.println("[" + Thread.currentThread().getName() + "] "
                + "RefuellingTruck: Refuelling " + planeName + "...");
            Thread.sleep(2000); // simulate refuelling time
            System.out.println("[" + Thread.currentThread().getName() + "] "
                + "RefuellingTruck: Refuelling complete for " + planeName + ".");
        } finally {
            lock.unlock(); // always release, even on interruption
        }
    }
}

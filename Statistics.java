import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe statistics collector.
 *
 * Concurrency mechanisms:
 *   AtomicInteger (Week 10) - CAS-based lock-free increment for counters.
 *     Multiple Airplane threads call incrementAndGet() concurrently; no
 *     synchronized block needed because each increment is one atomic CPU op.
 *
 *   Plain ArrayList + explicit synchronized block for waitingTimes.
 *     Avoids Collections.synchronizedList so only explicitly-taught
 *     concurrency primitives are used (per assignment restrictions).
 */
public class Statistics {

    /** Total planes that completed the full landing -> service -> takeoff cycle. */
    public final AtomicInteger totalPlanesServed      = new AtomicInteger(0);

    /** Total passengers who boarded across all flights. */
    public final AtomicInteger totalPassengersBoarded = new AtomicInteger(0);

    /** Waiting time (ms) per plane: request submitted -> landing permission granted. */
    private final List<Long> waitingTimes = new ArrayList<>();

    /** Thread-safe add: multiple Airplane threads may call this concurrently. */
    public synchronized void recordWaitingTime(long ms) {
        waitingTimes.add(ms);
    }

    /**
     * Prints sanity checks and final statistics.
     * Called from the main thread after all Airplane threads have joined,
     * so no concurrent access at this point.
     */
    public void printFinalStats(Gate[] gates) {
        System.out.println("\n==========================================");
        System.out.println("      ATC FINAL STATISTICS REPORT");
        System.out.println("==========================================");

        // Sanity check: all gates must be empty
        System.out.println("\n[Sanity Check] Gate Status:");
        boolean allClear = true;
        for (Gate g : gates) {
            boolean occ = g.isOccupied();
            System.out.printf("  Gate-%d : %s%n",
                g.getGateId(), occ ? "OCCUPIED  <<< ERROR >>>" : "Empty  (OK)");
            if (occ) allClear = false;
        }
        System.out.println("  Result : All gates empty = " + allClear);

        // Flight statistics
        System.out.println("\n[Flight Statistics]");
        System.out.println("  Planes Served         : " + totalPlanesServed.get());
        System.out.println("  Passengers Boarded    : " + totalPassengersBoarded.get());

        if (!waitingTimes.isEmpty()) {
            long   max = waitingTimes.stream().mapToLong(Long::longValue).max().orElse(0);
            long   min = waitingTimes.stream().mapToLong(Long::longValue).min().orElse(0);
            double avg = waitingTimes.stream().mapToLong(Long::longValue).average().orElse(0);
            System.out.printf("  Max Waiting Time (ms) : %d%n", max);
            System.out.printf("  Min Waiting Time (ms) : %d%n", min);
            System.out.printf("  Avg Waiting Time (ms) : %.1f%n", avg);
        }

        System.out.println("==========================================\n");
    }
}

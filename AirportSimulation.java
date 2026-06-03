import java.util.Random;

/**
 * Asia Pacific Airport Simulation
 * CT074-3-2 Concurrent Programming - Individual Assignment
 *
 * Arrival modes (toggle RANDOM_MODE below):
 *
 *   RANDOM_MODE = false  (default for submission/demo)
 *     Uses a fixed arrival schedule with gaps from {0,1,2} seconds per the
 *     assignment spec. Deterministically reproduces the required congested
 *     scenario every run:
 *       Plane-1 at t=0s, Plane-2 at t=0s  -> both land and occupy Gate-1, Gate-2
 *       Plane-3 at t=2s, Plane-4 at t=3s  -> both gates still occupied, hold in air
 *       Plane-5 EMERGENCY at t=4s         -> 2 planes already waiting, emergency jumps queue
 *       Plane-6 at t=5s                   -> normal, waits its turn
 *
 *   RANDOM_MODE = true  (alternative run mode)
 *     Uses rand.nextInt(3) * 1000 ms gaps (0, 1, or 2 seconds) as stated in
 *     the assignment spec. Congestion may vary per run.
 *
 * Thread summary:
 *   Main thread   - creates objects, starts Airplane threads, joins them, shuts down ATC
 *   ATC thread    - independent scheduling loop (extends Thread)
 *   Airplane-N    - 6 threads, one per plane, full 8-step lifecycle
 *   Passenger     - spawned per gate service, joined inside Airplane
 *   Refuel/Supply - spawned per gate service, joined inside Airplane
 *
 * Restricted libraries NOT used:
 *   ExecutorService, ForkJoinPool, CompletableFuture, ParallelStream,
 *   PriorityBlockingQueue, Timer, Reactive Streams, Spring @Async, Vert.x.
 */
public class AirportSimulation {

    // Set to true to use random 0/1/2 second gaps (per assignment spec).
    // Set to false (default) for the deterministic congested scenario demo.
    private static final boolean RANDOM_MODE = false;

    public static void main(String[] args) throws InterruptedException {

        System.out.println("=======================================================");
        System.out.println("    Asia Pacific Airport Simulation -- Starting");
        System.out.println("    Mode: " + (RANDOM_MODE ? "Random arrivals" : "Controlled congested scenario"));
        System.out.println("=======================================================");
        System.out.println();

        // Shared resources
        Runway          runway = new Runway();
        Gate[]          gates  = { new Gate(1), new Gate(2) };
        RefuellingTruck truck  = new RefuellingTruck();
        Statistics      stats  = new Statistics();

        // Start ATC thread
        ATC atc = new ATC(runway, gates);
        atc.start();

        // Plane-5 (index 4) is the emergency aircraft with fuel shortage.
        boolean[] isEmergency = { false, false, false, false, true, false };

        // Fixed arrival gaps in ms - all values are 0, 1, or 2 seconds (per spec).
        // Designed to guarantee the congested scenario:
        //   Planes 1+2 occupy both gates before Planes 3+4+5 arrive.
        long[] controlledGapsMs = { 0, 0, 2000, 1000, 1000, 1000 };

        Random rand = new Random();

        // Launch airplanes
        Airplane[] planes = new Airplane[6];
        for (int i = 0; i < planes.length; i++) {
            long gapMs;
            if (RANDOM_MODE) {
                gapMs = rand.nextInt(3) * 1000L; // 0, 1, or 2 seconds randomly
            } else {
                gapMs = controlledGapsMs[i];     // deterministic schedule
            }
            if (gapMs > 0) Thread.sleep(gapMs);

            planes[i] = new Airplane(i + 1, isEmergency[i], atc, truck, stats);
            planes[i].start();
        }

        // Wait for all planes to finish their full lifecycle
        for (Airplane plane : planes) {
            plane.join();
        }

        // Shut down ATC and print statistics
        atc.shutdown();
        atc.join();

        stats.printFinalStats(gates);
        System.out.println("Simulation complete.");
    }
}

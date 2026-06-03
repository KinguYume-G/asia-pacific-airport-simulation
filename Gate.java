/**
 * Represents a gate at the airport.
 * State is mutated exclusively inside ATC's synchronized block,
 * so no additional locking is needed here.
 */
public class Gate {
    private final int gateId;
    private boolean occupied = false;

    public Gate(int gateId) {
        this.gateId = gateId;
    }

    public int     getGateId()               { return gateId; }
    public boolean isOccupied()              { return occupied; }
    public void    setOccupied(boolean flag) { this.occupied = flag; }
}

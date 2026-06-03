/**
 * Represents the single runway.
 * State is mutated exclusively inside ATC's synchronized block.
 */
public class Runway {
    private boolean occupied = false;

    public boolean isOccupied()              { return occupied; }
    public void    setOccupied(boolean flag) { this.occupied = flag; }
}

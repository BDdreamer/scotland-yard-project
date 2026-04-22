package uk.ac.bris.cs.scotlandyard.ai;

import uk.ac.bris.cs.scotlandyard.model.Ticket;
import java.io.Serializable;
import java.util.*;

/**
 * Represents a game state in the endgame (rounds 20-24).
 * Used as key for endgame tablebase lookup.
 */
public class EndgameState implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final int mrXPosition;
    private final List<Integer> detectivePositions;  // ALWAYS sorted
    private final Map<Ticket, Integer> mrXTickets;
    private final int round;
    
    public EndgameState(int mrXPosition, List<Integer> detectivePositions, 
                       Map<Ticket, Integer> mrXTickets, int round) {
        this.mrXPosition = mrXPosition;
        // Sort detective positions for consistent hashing
        this.detectivePositions = new ArrayList<>(detectivePositions);
        Collections.sort(this.detectivePositions);
        this.mrXTickets = new HashMap<>(mrXTickets);
        this.round = round;
    }
    
    public int getMrXPosition() { return mrXPosition; }
    public List<Integer> getDetectivePositions() { return Collections.unmodifiableList(detectivePositions); }
    public Map<Ticket, Integer> getMrXTickets() { return Collections.unmodifiableMap(mrXTickets); }
    public int getRound() { return round; }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EndgameState that = (EndgameState) o;
        return mrXPosition == that.mrXPosition &&
               round == that.round &&
               Objects.equals(detectivePositions, that.detectivePositions) &&
               Objects.equals(mrXTickets, that.mrXTickets);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(mrXPosition, detectivePositions, mrXTickets, round);
    }
    
    @Override
    public String toString() {
        return String.format("EndgameState{mrX=%d, dets=%s, round=%d}", 
            mrXPosition, detectivePositions, round);
    }
}

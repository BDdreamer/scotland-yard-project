package uk.ac.bris.cs.scotlandyard.ai;

import uk.ac.bris.cs.scotlandyard.model.Move;
import java.io.Serializable;

/**
 * Entry in the endgame tablebase.
 * Stores the optimal move and whether this state is a WIN or LOSS.
 */
public class EndgameEntry implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final Move winningMove;
    private final boolean isWin;
    
    public EndgameEntry(Move winningMove, boolean isWin) {
        this.winningMove = winningMove;
        this.isWin = isWin;
    }
    
    public Move getWinningMove() { return winningMove; }
    public boolean isWin() { return isWin; }
    
    @Override
    public String toString() {
        return String.format("EndgameEntry{win=%s, move=%s}", isWin, winningMove);
    }
}

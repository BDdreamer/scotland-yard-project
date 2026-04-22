package uk.ac.bris.cs.scotlandyard.ai;

import uk.ac.bris.cs.scotlandyard.model.Colour;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.Player;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYardView;
import uk.ac.bris.cs.scotlandyard.model.Spectator;

import java.util.Collections;
import java.util.List;

/**
 * Factory wrapper for CombinedAI to make it available in the GUI.
 * The @ManagedAI annotation makes it show up in the player selection dropdown.
 */
@ManagedAI("Ultimate Mr. X")
public class CombinedAIFactory implements PlayerFactory {

    private CombinedAI mrX;
    
    @Override
    public Player createPlayer(Colour colour) {
        if (!colour.isMrX()) {
            throw new IllegalArgumentException("CombinedAIFactory only creates Mr. X player");
        }
        mrX = new CombinedAI();
        return mrX;
    }

    @Override
    public List<Spectator> createSpectators(ScotlandYardView view) {
        if (mrX == null) {
            mrX = new CombinedAI();
        }
        CombinedAI target = mrX;
        return Collections.singletonList(new Spectator() {
            @Override
            public void onMoveMade(ScotlandYardView v, Move move) {
                target.onMoveMade(v, move);
            }
        });
    }
}

package uk.ac.bris.cs.scotlandyard.ai;

import uk.ac.bris.cs.scotlandyard.model.Colour;
import uk.ac.bris.cs.scotlandyard.model.Player;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYardView;
import uk.ac.bris.cs.scotlandyard.model.Spectator;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;

/**
 * Factory for Coordinated Detective AI.
 * 
 * Creates five CoordinatedDetectiveAI instances that share a single Tracker
 * and a single CoordinationState for two-phase move coordination.
 */
@ManagedAI("Coordinated Detective")
public class CoordinatedDetectiveFactory implements PlayerFactory {
    
    private final ResourceProvider resourceProvider;
    private final GraphAnalyzer sharedGraphAnalyzer;
    private final CandidateDividingDetective.Tracker sharedTracker;
    private final CoordinationState sharedCoordinationState;
    
    /**
     * Constructor that creates shared instances for all detectives.
     * 
     * @param resourceProvider provides access to graph and other resources
     */
    public CoordinatedDetectiveFactory(ResourceProvider resourceProvider) {
        this.resourceProvider = resourceProvider;
        
        // Create single shared GraphAnalyzer instance
        this.sharedGraphAnalyzer = new GraphAnalyzer(resourceProvider.getGraph());
        
        // Create single shared Tracker instance (reused from CandidateDividingDetective)
        this.sharedTracker = new CandidateDividingDetective.Tracker(
            sharedGraphAnalyzer,
            resourceProvider.getGraph()
        );
        
        // Create single shared CoordinationState instance
        this.sharedCoordinationState = new CoordinationState();
    }
    
    /**
     * Creates a CoordinatedDetectiveAI player for the given colour.
     * All five detective instances share the same Tracker, CoordinationState, and GraphAnalyzer.
     * 
     * @param colour the detective colour
     * @return a new CoordinatedDetectiveAI instance
     * @throws IllegalArgumentException if colour is not a detective
     */
    @Nonnull
    @Override
    public Player createPlayer(@Nonnull Colour colour) {
        if (colour.isDetective()) {
            return new CoordinatedDetectiveAI(
                colour,
                sharedGraphAnalyzer,
                resourceProvider.getGraph(),
                sharedCoordinationState,
                sharedTracker
            );
        }
        throw new IllegalArgumentException("CoordinatedDetectiveFactory only creates detectives");
    }

    /**
     * Returns the list of spectators for the game.
     * Returns a list containing exactly the shared Tracker instance.
     * 
     * @param view the Scotland Yard view
     * @return list containing the shared Tracker
     */
    @Override
    public List<Spectator> createSpectators(ScotlandYardView view) {
        return Collections.singletonList(sharedTracker);
    }
}

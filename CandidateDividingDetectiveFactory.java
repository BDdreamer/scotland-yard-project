package uk.ac.bris.cs.scotlandyard.ai;

import uk.ac.bris.cs.scotlandyard.model.Colour;
import uk.ac.bris.cs.scotlandyard.model.Player;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYardView;
import uk.ac.bris.cs.scotlandyard.model.Spectator;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;

/**
 * Factory for Candidate-Dividing Detective AI.
 */
@ManagedAI("Candidate-Dividing Detective")
public class CandidateDividingDetectiveFactory implements PlayerFactory {
    
    private final ResourceProvider resourceProvider;
    private final CandidateDividingDetective.Tracker sharedTracker;
    
    public CandidateDividingDetectiveFactory(ResourceProvider resourceProvider) {
        this.resourceProvider = resourceProvider;
        this.sharedTracker = new CandidateDividingDetective.Tracker(
            new GraphAnalyzer(resourceProvider.getGraph()),
            resourceProvider.getGraph()
        );
    }
    
    @Nonnull
    @Override
    public Player createPlayer(@Nonnull Colour colour) {
        if (colour.isDetective()) {
            return new CandidateDividingDetective(
                colour,
                new GraphAnalyzer(resourceProvider.getGraph()),
                sharedTracker
            );
        }
        throw new IllegalArgumentException("CandidateDividingDetectiveFactory only creates detectives");
    }

    @Override
    public List<Spectator> createSpectators(ScotlandYardView view) {
        return Collections.singletonList(sharedTracker);
    }
}

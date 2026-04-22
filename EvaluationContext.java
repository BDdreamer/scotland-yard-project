package uk.ac.bris.cs.scotlandyard.ai;

import uk.ac.bris.cs.scotlandyard.model.Ticket;
import java.util.Map;
import java.util.Set;

/**
 * Context object containing all information needed for move evaluation.
 * 
 * This avoids passing dozens of parameters to scoring functions and makes
 * it easy to add new context without changing method signatures.
 * 
 * Design Pattern: Context Object / Parameter Object
 */
public class EvaluationContext {
    
    // Core game state
    private final int mrXLocation;
    private final Set<Integer> detectiveLocations;
    private final Map<Ticket, Integer> mrXTickets;
    private final int currentRound;
    private final int totalRounds;
    
    // Computed metrics (cached for performance)
    private final double gameProgress;
    private final int roundsUntilReveal;
    private final boolean isRevealRound;
    
    // Helper objects
    private final GraphAnalyzer graphAnalyzer;
    private final BeliefStateTracker beliefTracker;
    
    // Optional components (may be null)
    private final SurfaceTurnPlanner surfacePlanner;
    private final CordonDetector cordonDetector;
    private final ZoneStrategy zoneStrategy;
    
    private EvaluationContext(Builder builder) {
        this.mrXLocation = builder.mrXLocation;
        this.detectiveLocations = builder.detectiveLocations;
        this.mrXTickets = builder.mrXTickets;
        this.currentRound = builder.currentRound;
        this.totalRounds = builder.totalRounds;
        this.gameProgress = (double) currentRound / totalRounds;
        this.roundsUntilReveal = builder.roundsUntilReveal;
        this.isRevealRound = builder.isRevealRound;
        this.graphAnalyzer = builder.graphAnalyzer;
        this.beliefTracker = builder.beliefTracker;
        this.surfacePlanner = builder.surfacePlanner;
        this.cordonDetector = builder.cordonDetector;
        this.zoneStrategy = builder.zoneStrategy;
    }
    
    // Getters
    public int getMrXLocation() { return mrXLocation; }
    public Set<Integer> getDetectiveLocations() { return detectiveLocations; }
    public Map<Ticket, Integer> getMrXTickets() { return mrXTickets; }
    public int getCurrentRound() { return currentRound; }
    public int getTotalRounds() { return totalRounds; }
    public double getGameProgress() { return gameProgress; }
    public int getRoundsUntilReveal() { return roundsUntilReveal; }
    public boolean isRevealRound() { return isRevealRound; }
    public GraphAnalyzer getGraphAnalyzer() { return graphAnalyzer; }
    public BeliefStateTracker getBeliefTracker() { return beliefTracker; }
    public SurfaceTurnPlanner getSurfacePlanner() { return surfacePlanner; }
    public CordonDetector getCordonDetector() { return cordonDetector; }
    public ZoneStrategy getZoneStrategy() { return zoneStrategy; }
    
    /**
     * Builder for EvaluationContext (fluent API)
     */
    public static class Builder {
        // Required parameters
        private final int mrXLocation;
        private final Set<Integer> detectiveLocations;
        private final GraphAnalyzer graphAnalyzer;
        
        // Optional parameters with defaults
        private Map<Ticket, Integer> mrXTickets = Map.of();
        private int currentRound = 1;
        private int totalRounds = 24;
        private int roundsUntilReveal = 3;
        private boolean isRevealRound = false;
        private BeliefStateTracker beliefTracker = null;
        private SurfaceTurnPlanner surfacePlanner = null;
        private CordonDetector cordonDetector = null;
        private ZoneStrategy zoneStrategy = null;
        
        public Builder(int mrXLocation, Set<Integer> detectiveLocations, GraphAnalyzer graphAnalyzer) {
            this.mrXLocation = mrXLocation;
            this.detectiveLocations = detectiveLocations;
            this.graphAnalyzer = graphAnalyzer;
        }
        
        public Builder mrXTickets(Map<Ticket, Integer> tickets) {
            this.mrXTickets = tickets;
            return this;
        }
        
        public Builder currentRound(int round) {
            this.currentRound = round;
            return this;
        }
        
        public Builder totalRounds(int rounds) {
            this.totalRounds = rounds;
            return this;
        }
        
        public Builder roundsUntilReveal(int rounds) {
            this.roundsUntilReveal = rounds;
            return this;
        }
        
        public Builder isRevealRound(boolean isReveal) {
            this.isRevealRound = isReveal;
            return this;
        }
        
        public Builder beliefTracker(BeliefStateTracker tracker) {
            this.beliefTracker = tracker;
            return this;
        }
        
        public Builder surfacePlanner(SurfaceTurnPlanner planner) {
            this.surfacePlanner = planner;
            return this;
        }
        
        public Builder cordonDetector(CordonDetector detector) {
            this.cordonDetector = detector;
            return this;
        }
        
        public Builder zoneStrategy(ZoneStrategy strategy) {
            this.zoneStrategy = strategy;
            return this;
        }
        
        public EvaluationContext build() {
            return new EvaluationContext(this);
        }
    }
}

package uk.ac.bris.cs.scotlandyard.ai;

import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYardView;

/**
 * Interface for modular scoring components.
 * 
 * Each component evaluates a specific aspect of a move (e.g., safety, mobility, ticket usage)
 * and returns a normalized score. This allows for:
 * - Better testability (each component can be tested independently)
 * - Easier tuning (adjust weights without changing evaluation logic)
 * - Clearer code organization (single responsibility per component)
 * 
 * Design Pattern: Strategy Pattern for move evaluation
 */
public interface ScoringComponent {
    
    /**
     * Evaluate a move for this specific component.
     * 
     * @param move The move to evaluate
     * @param view Current game state
     * @param context Additional context needed for evaluation
     * @return Normalized score in range [-1.0, 1.0] where:
     *         -1.0 = worst possible for this component
     *          0.0 = neutral
     *         +1.0 = best possible for this component
     */
    double evaluate(Move move, ScotlandYardView view, EvaluationContext context);
    
    /**
     * Get the weight/importance of this component.
     * Higher weight = more influence on final score.
     * 
     * @return Weight value (typically 0.0 to 10.0)
     */
    double getWeight();
    
    /**
     * Get a human-readable name for this component.
     * Used for debugging and logging.
     * 
     * @return Component name (e.g., "Safety", "Mobility", "Ticket Conservation")
     */
    String getName();
    
    /**
     * Check if this component should be active for the given game state.
     * Some components may only be relevant in certain game phases.
     * 
     * @param view Current game state
     * @return true if component should contribute to score, false otherwise
     */
    default boolean isActive(ScotlandYardView view) {
        return true;  // Active by default
    }
}

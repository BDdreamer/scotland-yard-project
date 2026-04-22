package uk.ac.bris.cs.scotlandyard.ai;

import uk.ac.bris.cs.scotlandyard.model.Transport;
import java.util.Set;

import static uk.ac.bris.cs.scotlandyard.ai.ScoringConfig.*;

/**
 * Pure functions for ticket scoring logic - easily testable without game state.
 * 
 * All magic numbers have been extracted to ScoringConfig for easier tuning.
 * This class contains only the scoring logic itself.
 */
public class TicketScoring {
    
    /**
     * Calculate SECRET ticket score based on transport diversity and reveal timing
     * 
     * @param availableTransports Transport types available at CURRENT node
     * @param roundsUntilReveal How many rounds until next reveal (1 = next round is reveal)
     * @param minDetectiveDistance Shortest path to nearest detective
     * @param secretsRemaining Number of SECRET tickets left
     * @param remainingReveals Number of reveal rounds left in game
     * @param gameProgress 0.0 to 1.0 representing game completion
     * @return Score adjustment for using SECRET
     */
    public static double scoreSecretTicket(
            Set<Transport> availableTransports,
            int roundsUntilReveal,
            int minDetectiveDistance,
            int secretsRemaining,
            int remainingReveals,
            double gameProgress) {
        
        double score = 0.0;
        
        // Single-transport node provides zero ambiguity
        if (availableTransports.size() == 1) {
            return SECRET_SINGLE_TRANSPORT_PENALTY;
        }
        
        // Transport diversity multiplier: 2 types = 1.0x, 3 types = 1.5x
        double diversityMultiplier = availableTransports.size() / 2.0;
        
        // Ticket reservation: save SECRETs for reveal rounds
        boolean shouldReserveSecret = secretsRemaining <= remainingReveals;
        
        // SECRET stack bonus before reveals (maximum ambiguity)
        if (roundsUntilReveal <= 2 && secretsRemaining >= 1) {
            double baseBonus = getSecretPreRevealBonus(minDetectiveDistance, gameProgress);
            
            // Apply diversity multiplier
            score += baseBonus * diversityMultiplier;
            
            // Stack bonus for using SECRET 2 moves before reveal
            if (roundsUntilReveal == 2) {
                score += SECRET_STACK_BONUS * diversityMultiplier;
            }
        }
        // Not before reveal - check if we should reserve
        else if (shouldReserveSecret && roundsUntilReveal > 2) {
            score += SECRET_RESERVATION_PENALTY;
        }
        
        return score;
    }
    
    /**
     * Calculate DOUBLE ticket score based on reveal timing
     * 
     * @param firstMoveOnReveal Is the first move of DOUBLE on a reveal round?
     * @param secondMoveOnReveal Is the second move of DOUBLE on a reveal round?
     * @return Score adjustment for using DOUBLE
     */
    public static double scoreDoubleOnReveal(boolean firstMoveOnReveal, boolean secondMoveOnReveal) {
        // DOUBLE on reveal = catastrophic info leak (detectives see both moves clearly)
        if (firstMoveOnReveal || secondMoveOnReveal) {
            return DOUBLE_ON_REVEAL_PENALTY;
        }
        
        return 0.0;
    }
    
    /**
     * Calculate penalty for bus corridor funnels
     * 
     * @param busNeighbors Number of bus connections at destination
     * @return Score adjustment
     */
    public static double scoreBusCorridorFunnel(int busNeighbors) {
        // Bus corridor funnel detection
        if (busNeighbors <= BUS_FUNNEL_THRESHOLD) {
            return BUS_FUNNEL_PENALTY;
        }
        return 0.0;
    }
    
    /**
     * Calculate penalty for underground isolation
     * 
     * @param undergroundNeighbors Number of underground connections at destination
     * @param roundsUntilReveal Rounds until next reveal
     * @return Score adjustment
     */
    public static double scoreUndergroundIsolation(int undergroundNeighbors, int roundsUntilReveal) {
        double score = 0.0;
        
        // Underground isolation check
        if (undergroundNeighbors <= UNDERGROUND_ISOLATION_THRESHOLD) {
            score += UNDERGROUND_ISOLATION_PENALTY;
        } else if (undergroundNeighbors >= UNDERGROUND_GOOD_CONNECTIVITY_THRESHOLD) {
            score += UNDERGROUND_CONNECTIVITY_BONUS;
        }
        
        // Extra penalty near reveals
        if (roundsUntilReveal == 1 && undergroundNeighbors <= BUS_FUNNEL_THRESHOLD) {
            score += UNDERGROUND_ISOLATION_NEAR_REVEAL_PENALTY;
        }
        
        return score;
    }
}

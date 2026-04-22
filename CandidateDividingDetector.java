package uk.ac.bris.cs.scotlandyard.ai;

import uk.ac.bris.cs.scotlandyard.model.Ticket;
import uk.ac.bris.cs.scotlandyard.model.Transport;
import java.util.*;

/**
 * Detects candidate-dividing detective strategy - where detectives coordinate to 
 * divide and reduce Mr. X's belief state (possible locations).
 * 
 * Unlike hub-blocking (stationary traps), candidate-dividing detectives:
 * 1. Track the belief state (set of possible Mr. X locations)
 * 2. Divide coverage among themselves - each covers a subset
 * 3. Prioritize moves that maximize candidate elimination
 * 4. Try to "corner" Mr. X by systematically eliminating possibilities
 * 
 * This detector helps Mr. X recognize when detectives are using this strategy
 * and respond by maximizing ambiguity (increasing candidate set size).
 */
public class CandidateDividingDetector {
    
    private final GraphAnalyzer graphAnalyzer;
    private final BeliefStateTracker beliefTracker;
    
    public CandidateDividingDetector(GraphAnalyzer graphAnalyzer, BeliefStateTracker beliefTracker) {
        this.graphAnalyzer = graphAnalyzer;
        this.beliefTracker = beliefTracker;
    }
    
    /**
     * Calculate candidate pressure score - how effectively detectives are dividing
     * and reducing the belief state.
     * 
     * Returns 0-100 where 100 = detectives have effectively divided and cornered Mr. X.
     */
    public double calculateCandidatePressure(int mrXLocation, Set<Integer> detectiveLocations) {
        if (detectiveLocations.size() < 2) return 0;
        
        // 1. Get current belief state size
        Set<Integer> candidateSet = beliefTracker.getPossibleLocations();
        int candidateSetSize = candidateSet.size();
        
        // 2. Small candidate set is already dangerous (easy to corner)
        double sizePressure = calculateSizePressure(candidateSetSize);
        
        // 3. Calculate how well detectives cover the candidate set
        double coveragePressure = calculateCoveragePressure(candidateSet, detectiveLocations);
        
        // 4. Calculate convergence pressure - are detectives moving to eliminate candidates?
        double convergencePressure = calculateConvergencePressure(candidateSet, detectiveLocations);
        
        // Combine pressures
        return Math.min(100, sizePressure * 0.4 + coveragePressure * 0.4 + convergencePressure * 0.2);
    }
    
    /**
     * Calculate pressure from small candidate set size.
     * Smaller set = easier for detectives to corner you.
     */
    private double calculateSizePressure(int candidateSetSize) {
        if (candidateSetSize <= 3) return 95;      // Very dangerous
        if (candidateSetSize <= 5) return 75;      // Dangerous
        if (candidateSetSize <= 8) return 55;      // Moderate
        if (candidateSetSize <= 12) return 35;     // Elevated danger
        if (candidateSetSize <= 15) return 20;     // Warning zone
        return 8;                                   // Safe (large candidate set)
    }
    
    /**
     * Calculate how well detectives cover the candidate set.
     * Each detective covers candidates within 2 moves.
     * High coverage = effective candidate-dividing.
     */
    private double calculateCoveragePressure(Set<Integer> candidateSet, Set<Integer> detectiveLocations) {
        if (candidateSet.isEmpty()) return 0;
        
        Set<Integer> coveredCandidates = new HashSet<>();
        
        for (int detLoc : detectiveLocations) {
            Set<Integer> detReachable = getReachableInNMoves(detLoc, 2);
            // Intersection: which candidates can this detective reach?
            Set<Integer> detCoverage = new HashSet<>(detReachable);
            detCoverage.retainAll(candidateSet);
            coveredCandidates.addAll(detCoverage);
        }
        
        // Coverage ratio: what % of candidates are within detective reach
        double coverageRatio = (double) coveredCandidates.size() / candidateSet.size();
        
        // High coverage means detectives are actively dividing the belief state.
        if (coverageRatio > 0.95) return 95;
        if (coverageRatio > 0.85) return 80;
        if (coverageRatio > 0.65) return 60;
        if (coverageRatio > 0.45) return 40;
        return 15;
    }
    
    /**
     * Calculate convergence pressure - are detectives moving toward candidates?
     * Detectives moving toward belief centroid = trying to corner Mr. X.
     */
    private double calculateConvergencePressure(Set<Integer> candidateSet, Set<Integer> detectiveLocations) {
        if (candidateSet.isEmpty() || detectiveLocations.isEmpty()) return 0;
        
        // Calculate centroid of candidate set (where detectives think Mr. X is)
        int centroid = calculateCentroid(candidateSet);
        
        // Count detectives moving toward centroid
        int convergingDetectives = 0;
        for (int detLoc : detectiveLocations) {
            int distToCentroid = graphAnalyzer.calculateShortestPath(detLoc, centroid);
            if (distToCentroid >= 0 && distToCentroid <= 3) {
                convergingDetectives++;
            }
        }
        
        // Multiple detectives converging on centroid = coordinated candidate-dividing
        if (convergingDetectives >= 3) return 90;
        if (convergingDetectives >= 2) return 65;
        if (convergingDetectives >= 1) return 30;
        return 5;
    }
    
    /**
     * Calculate how "eliminate-able" a move is.
     * Moves to nodes with unique characteristics are easier to eliminate.
     * 
     * Returns score 0-100 where 100 = very easy for detectives to eliminate this move.
     */
    public double calculateEliminability(int destination, Ticket ticket) {
        double eliminability = 0;
        
        // 1. Isolated nodes are easy to eliminate (only one way in/out)
        int connectivity = graphAnalyzer.getConnectivity(destination);
        if (connectivity <= 1) {
            eliminability += 40;  // Dead end = very easy to eliminate
        } else if (connectivity <= 2) {
            eliminability += 25;  // Low connectivity = somewhat easy
        }
        
        // 2. Unique transport types are easy to track
        // If destination requires rare transport, detectives can filter by ticket type
        Set<Transport> transports = getAvailableTransports(destination);
        if (transports.size() == 1) {
            eliminability += 30;  // Single transport type = easy to track
        }
        
        // 3. Ferry nodes are unique and easy to eliminate (only 1 ferry in game)
        if (transports.contains(Transport.FERRY)) {
            eliminability += 35;  // Ferry is very distinctive
        }
        
        // 4. Underground stations are sparse - easy to enumerate
        if (transports.contains(Transport.UNDERGROUND) && connectivity <= 2) {
            eliminability += 20;  // Isolated underground = small candidate set
        }
        
        return Math.min(100, eliminability);
    }
    
    /**
     * Check if a move would be a "candidate-dividing trap" - a move that's
     * easy to eliminate when the candidate set is small.
     */
    public boolean isCandidateTrap(int destination, Ticket ticket, Set<Integer> detectiveLocations) {
        Set<Integer> candidateSet = beliefTracker.getPossibleLocations();
        int candidateSetSize = candidateSet.size();
        
        // Only dangerous when candidate set is already small
        if (candidateSetSize > 10) return false;
        
        // Calculate eliminability of this move
        double eliminability = calculateEliminability(destination, ticket);
        
        // Calculate candidate pressure
        double pressure = calculateCandidatePressure(destination, detectiveLocations);
        
        // Trap = high eliminability + high pressure + small candidate set
        return eliminability > 50 && pressure > 50 && candidateSetSize <= 8;
    }
    
    /**
     * Get all nodes reachable within N moves.
     */
    private Set<Integer> getReachableInNMoves(int start, int maxMoves) {
        Set<Integer> reachable = new HashSet<>();
        Queue<int[]> queue = new LinkedList<>();
        queue.offer(new int[]{start, 0});
        reachable.add(start);
        
        while (!queue.isEmpty()) {
            int[] current = queue.poll();
            int loc = current[0];
            int moves = current[1];
            
            if (moves >= maxMoves) continue;
            
            Set<Integer> adjacent = graphAnalyzer.getAdjacentLocations(loc);
            for (int next : adjacent) {
                if (!reachable.contains(next)) {
                    reachable.add(next);
                    queue.offer(new int[]{next, moves + 1});
                }
            }
        }
        
        return reachable;
    }
    
    /**
     * Calculate centroid (average position) of candidate set.
     */
    private int calculateCentroid(Set<Integer> candidates) {
        if (candidates.isEmpty()) return 0;
        
        int sum = 0;
        for (int loc : candidates) {
            sum += loc;
        }
        return sum / candidates.size();
    }
    
    /**
     * Get available transport types at a location.
     */
    private Set<Transport> getAvailableTransports(int location) {
        Set<Transport> transports = new HashSet<>();
        Set<Integer> adjacent = graphAnalyzer.getAdjacentLocations(location);
        
        for (int dest : adjacent) {
            Transport t = graphAnalyzer.findTransportBetween(location, dest);
            if (t != null) {
                transports.add(t);
            }
        }
        
        return transports;
    }
    
    /**
     * Calculate recommended response: maximize candidate set growth.
     * Returns a score bonus for moves that increase ambiguity.
     */
    public double calculateAmbiguityBonus(int currentLocation, Ticket ticket, int destination) {
        Set<Integer> candidateSet = beliefTracker.getPossibleLocations();
        int candidateSetSize = candidateSet.size();
        
        // If candidate set is small, we need to maximize growth
        if (candidateSetSize > 15) return 0;  // Already ambiguous enough
        
        // Simulate what candidate set would be after this move
        int projectedSize = simulateCandidateSetGrowth(currentLocation, ticket, destination);
        
        // Bonus proportional to growth
        int growth = projectedSize - candidateSetSize;
        if (growth > 5) return 50;   // Large growth = good
        if (growth > 2) return 25;   // Moderate growth
        if (growth > 0) return 10;   // Small growth
        return -20;                   // Shrinking = bad
    }
    
    /**
     * Simulate candidate set size after a move.
     */
    private int simulateCandidateSetGrowth(int currentLocation, Ticket ticket, int destination) {
        // Simplified: estimate based on destination connectivity and transport diversity
        int connectivity = graphAnalyzer.getConnectivity(destination);
        Set<Transport> transports = getAvailableTransports(destination);
        
        // Higher connectivity + more transports = more candidates can reach it
        return connectivity * (1 + transports.size());
    }
}

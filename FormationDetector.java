package uk.ac.bris.cs.scotlandyard.ai;

import java.util.*;

/**
 * Detects coordinated detective formations (nets/traps) that individual position scoring misses.
 * 
 * This is the key to beating coordinated opponents: recognizing when individual safe positions
 * form a collective trap.
 */
public class FormationDetector {
    
    private final GraphAnalyzer graphAnalyzer;
    
    public FormationDetector(GraphAnalyzer graphAnalyzer) {
        this.graphAnalyzer = graphAnalyzer;
    }
    
    /**
     * Calculate formation risk - how well detectives form a coordinated "net" around Mr. X.
     * Returns 0-100 where 100 = complete encirclement trap.
     * 
     * Key insight: Individual distances may be safe (>3) but collective positioning
     * can still form an inescapable trap.
     */
    public double calculateFormationRisk(int mrXLocation, Set<Integer> detectiveLocations) {
        if (detectiveLocations.size() < 3) return 0; // Need 3+ for net formation
        
        // 1. Get Mr. X's escape options (nodes reachable in 2 moves)
        Set<Integer> mrXReachable = getReachableInNMoves(mrXLocation, 2);
        if (mrXReachable.isEmpty()) return 100; // Already trapped
        
        // 2. For each escape route, check if detectives can intercept
        Map<Integer, Double> escapeRouteRisk = new HashMap<>();
        for (int escapeNode : mrXReachable) {
            double risk = calculateEscapeRouteRisk(escapeNode, detectiveLocations, mrXLocation);
            escapeRouteRisk.put(escapeNode, risk);
        }
        
        // 3. Calculate what % of escape routes are high-risk (>70% interception chance)
        int highRiskRoutes = 0;
        int totalRoutes = escapeRouteRisk.size();
        for (double risk : escapeRouteRisk.values()) {
            if (risk > 70) highRiskRoutes++;
        }
        
        // 4. Check angular coverage - are detectives spread to cover all directions?
        double angularCoverage = calculateAngularCoverage(mrXLocation, detectiveLocations);
        
        // 5. Combine metrics: high coverage + high risk routes = trap
        double coverageComponent = angularCoverage * 0.6;
        double routeComponent = (totalRoutes > 0) ? 
            ((double) highRiskRoutes / totalRoutes) * 40 : 0;
        
        return Math.min(100, coverageComponent + routeComponent);
    }
    
    /**
     * Calculate risk for a specific escape route.
     * Risk = how likely detectives can intercept Mr. X before he reaches safety.
     */
    private double calculateEscapeRouteRisk(int escapeNode, Set<Integer> detectiveLocations, int mrXLocation) {
        int mrXDist = graphAnalyzer.calculateShortestPath(mrXLocation, escapeNode);
        if (mrXDist < 0) return 100; // Unreachable
        
        // Find closest detective to this escape route
        int minDetDist = Integer.MAX_VALUE;
        for (int detLoc : detectiveLocations) {
            int dist = graphAnalyzer.calculateShortestPath(detLoc, escapeNode);
            if (dist >= 0 && dist < minDetDist) {
                minDetDist = dist;
            }
        }
        
        // Risk high if detective can reach escape node at same time or before Mr. X
        if (minDetDist <= mrXDist) return 90; // Interception likely
        if (minDetDist == mrXDist + 1) return 70; // Very close
        if (minDetDist == mrXDist + 2) return 40; // Moderate risk
        return 10; // Low risk
    }
    
    /**
     * Calculate how well detectives are spread to cover all escape directions.
     * Returns 0-60 where 60 = perfect coverage from all directions.
     */
    private double calculateAngularCoverage(int mrXLocation, Set<Integer> detectiveLocations) {
        // Get Mr. X's immediate exits
        Set<Integer> exits = graphAnalyzer.getAdjacentLocations(mrXLocation);
        if (exits.isEmpty()) return 60; // Trapped
        
        // For each exit, find the closest detective
        int coveredExits = 0;
        for (int exit : exits) {
            int minDetDist = Integer.MAX_VALUE;
            for (int detLoc : detectiveLocations) {
                int dist = graphAnalyzer.calculateShortestPath(detLoc, exit);
                if (dist >= 0 && dist < minDetDist) {
                    minDetDist = dist;
                }
            }
            // Exit is "covered" if a detective is within 2 moves
            if (minDetDist <= 2) coveredExits++;
        }
        
        // Coverage score: what % of exits are threatened
        double coverageRatio = exits.isEmpty() ? 1.0 : (double) coveredExits / exits.size();
        return coverageRatio * 60; // Max 60 points for angular coverage
    }
    
    /**
     * Detect if moving to a position would walk into a tightening trap.
     * Returns true if destination is part of a multi-move trap pattern.
     */
    public boolean isTrapDestination(int destination, Set<Integer> detectiveLocations, int mrXTickets) {
        // Simulate 2 moves ahead: if all future positions are also high-risk, it's a trap
        Set<Integer> futurePositions = getReachableInNMoves(destination, 2);
        
        int trappedFutures = 0;
        for (int future : futurePositions) {
            double futureRisk = calculateFormationRisk(future, detectiveLocations);
            if (futureRisk > 80) trappedFutures++;
        }
        
        // If >70% of future positions are also trapped, this is a trap destination
        double trapRatio = futurePositions.isEmpty() ? 0 : 
            (double) trappedFutures / futurePositions.size();
        return trapRatio > 0.7;
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
}

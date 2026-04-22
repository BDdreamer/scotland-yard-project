package uk.ac.bris.cs.scotlandyard.ai;

import java.util.*;

/**
 * Detects when detectives are forming a cordon (encirclement) around Mr. X.
 * 
 * A position at distance 5 from every detective is TERRIBLE if those detectives
 * surround you in a circle. This detector identifies such situations.
 */
public class CordonDetector {
    
    private final GraphAnalyzer graphAnalyzer;
    
    public CordonDetector(GraphAnalyzer graphAnalyzer) {
        this.graphAnalyzer = graphAnalyzer;
    }
    
    /**
     * Calculate encirclement risk score with FORWARD PROJECTION.
     * Returns 0-100 where 100 = completely surrounded.
     * 
     * SCENARIO 4 FIX: Detectives play forward - they position for where you'll be
     * in 2 moves, not where you are now. This projects the cordon forward.
     * 
     * Algorithm:
     * 1. Get all nodes reachable by Mr. X in 2 moves FROM destination
     * 2. For each reachable node, check if detectives can reach it in 2 moves
     * 3. Calculate what % of escape routes are covered by detectives
     * 4. Project detective positions forward by 1 move
     */
    public double calculateEncirclementRisk(int mrXLocation, Set<Integer> detectiveLocations) {
        if (detectiveLocations.size() < 2) return 0; // Need at least 2 for cordon
        
        // FORWARD PROJECTION: Where will Mr. X be able to go in 2 moves?
        Set<Integer> mrXReachable = getReachableNodes(mrXLocation, 2);
        if (mrXReachable.isEmpty()) return 100; // Trapped!
        
        // FORWARD PROJECTION: Where will detectives be able to reach in 2 moves?
        // But also consider where they'll position in 1 move to cut off Mr. X
        Set<Integer> detectiveReachableNow = new HashSet<>();
        Set<Integer> detectiveReachableNext = new HashSet<>();
        
        for (int detLoc : detectiveLocations) {
            detectiveReachableNow.addAll(getReachableNodes(detLoc, 2));
            
            // Project forward: where can detective be in 1 move?
            Set<Integer> detectiveNextPositions = getReachableNodes(detLoc, 1);
            for (int nextPos : detectiveNextPositions) {
                // From each next position, where can they reach in 1 more move?
                detectiveReachableNext.addAll(getReachableNodes(nextPos, 1));
            }
        }
        
        // Calculate overlap - how many of Mr. X's escape routes are covered
        int coveredNow = 0;
        int coveredNext = 0;
        
        for (int node : mrXReachable) {
            if (detectiveReachableNow.contains(node)) {
                coveredNow++;
            }
            if (detectiveReachableNext.contains(node)) {
                coveredNext++;
            }
        }
        
        double coverageRatioNow = (double) coveredNow / mrXReachable.size();
        double coverageRatioNext = (double) coveredNext / mrXReachable.size();
        
        // Use the WORSE of current and projected coverage
        double effectiveCoverage = Math.max(coverageRatioNow, coverageRatioNext);
        
        // Check angular distribution - are detectives spread around Mr. X?
        double angularSpread = calculateAngularSpread(mrXLocation, detectiveLocations);
        
        // Combine coverage and spread
        // High coverage + high spread = cordon
        double encirclementRisk = (effectiveCoverage * 0.7 + angularSpread * 0.3) * 100;
        
        return Math.min(encirclementRisk, 100);
    }
    
    /**
     * Get all nodes reachable within N moves.
     */
    private Set<Integer> getReachableNodes(int start, int maxMoves) {
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
     * Calculate how evenly detectives are distributed around Mr. X.
     * Returns 0-1 where 1 = perfectly surrounding from all directions.
     * 
     * Uses a simplified angular distribution check:
     * - Divide space into 4 quadrants
     * - Check how many quadrants have detectives
     */
    private double calculateAngularSpread(int mrXLocation, Set<Integer> detectiveLocations) {
        if (detectiveLocations.size() < 2) return 0;
        
        // Simplified: check if detectives are in different "directions"
        // by looking at which adjacent nodes they're closest to
        Set<Integer> mrXExits = graphAnalyzer.getAdjacentLocations(mrXLocation);
        if (mrXExits.isEmpty()) return 1.0; // Trapped
        
        // For each exit, find closest detective
        Map<Integer, Integer> exitToClosestDetective = new HashMap<>();
        for (int exit : mrXExits) {
            int closestDet = -1;
            int minDist = Integer.MAX_VALUE;
            
            for (int detLoc : detectiveLocations) {
                int dist = graphAnalyzer.calculateShortestPath(exit, detLoc);
                if (dist >= 0 && dist < minDist) {
                    minDist = dist;
                    closestDet = detLoc;
                }
            }
            
            if (closestDet != -1) {
                exitToClosestDetective.put(exit, closestDet);
            }
        }
        
        // Count unique detectives threatening different exits
        Set<Integer> uniqueThreateningDetectives = new HashSet<>(exitToClosestDetective.values());
        
        // More detectives threatening different exits = better spread
        return Math.min(1.0, (double) uniqueThreateningDetectives.size() / Math.min(detectiveLocations.size(), 4));
    }
    
    /**
     * Check if Mr. X should enter "escape mode" - ignore ambiguity, maximize distance.
     */
    public boolean shouldEnterEscapeMode(int mrXLocation, Set<Integer> detectiveLocations) {
        double encirclementRisk = calculateEncirclementRisk(mrXLocation, detectiveLocations);
        
        // Enter escape mode if:
        // 1. High encirclement risk (>60%)
        // 2. OR any detective within distance 2
        if (encirclementRisk > 60) return true;
        
        for (int detLoc : detectiveLocations) {
            int dist = graphAnalyzer.calculateShortestPath(mrXLocation, detLoc);
            if (dist >= 0 && dist <= 2) return true;
        }
        
        return false;
    }
}

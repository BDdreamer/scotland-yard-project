package uk.ac.bris.cs.scotlandyard.ai;

import java.util.*;

/**
 * Proactive Zone Ownership Strategy.
 * 
 * The London board has "safe zones" - high-connectivity clusters where even
 * an encircled Mr. X has many escape vectors. This class precomputes and
 * manages strategic positioning across the whole game arc.
 * 
 * This is the difference between an AI that SURVIVES and one that DOMINATES.
 */
public class ZoneStrategy {
    
    private final GraphAnalyzer graphAnalyzer;
    private final Map<Integer, Double> safetyMap;
    private final Map<Integer, Integer> zoneAssignment;
    private final int totalNodes;
    
    // Zone centers (high-connectivity hubs on London board)
    // These are empirically strong positions
    private static final int[] ZONE_CENTERS = {
        13, 29, 46, 67, 89, 108, 128, 140, 155, 174
    };
    
    public ZoneStrategy(GraphAnalyzer graphAnalyzer, int totalNodes) {
        this.graphAnalyzer = graphAnalyzer;
        this.totalNodes = totalNodes;
        this.safetyMap = new HashMap<>();
        this.zoneAssignment = new HashMap<>();
        
        precomputeSafetyMap();
        assignZones();
    }
    
    /**
     * Precompute static safety score for every node.
     * 
     * Safety = long-run survivability, not just immediate exits.
     * Factors:
     * - Connectivity (more exits = safer)
     * - Transport diversity (multiple escape types)
     * - Neighbor connectivity (exits also have good exits)
     * - Centrality (not in a corner)
     */
    private void precomputeSafetyMap() {
        for (int node = 1; node <= totalNodes; node++) {
            double safety = 0;
            
            // 1. Direct connectivity (weight: 10)
            int connectivity = graphAnalyzer.getConnectivity(node);
            safety += connectivity * 10.0;
            
            // 2. Transport diversity (weight: 8)
            Set<Integer> adjacent = graphAnalyzer.getAdjacentLocations(node);
            Set<String> transportTypes = new HashSet<>();
            for (int adj : adjacent) {
                var transport = graphAnalyzer.findTransportBetween(node, adj);
                if (transport != null) {
                    transportTypes.add(transport.toString());
                }
            }
            safety += transportTypes.size() * 8.0;
            
            // 3. Neighbor connectivity - average exits from exits (weight: 5)
            double avgNeighborConnectivity = 0;
            if (!adjacent.isEmpty()) {
                for (int adj : adjacent) {
                    avgNeighborConnectivity += graphAnalyzer.getConnectivity(adj);
                }
                avgNeighborConnectivity /= adjacent.size();
            }
            safety += avgNeighborConnectivity * 5.0;
            
            // 4. Centrality - distance from board edges (weight: 3)
            // Nodes with high average distance to all other nodes are more central
            double centrality = calculateCentrality(node);
            safety += centrality * 3.0;
            
            // 5. Escape volume - nodes reachable in 2 moves (weight: 2)
            int escapeVolume = getReachableCount(node, 2);
            safety += escapeVolume * 2.0;
            
            safetyMap.put(node, safety);
        }
    }
    
    /**
     * Assign each node to nearest zone center.
     */
    private void assignZones() {
        for (int node = 1; node <= totalNodes; node++) {
            int closestZone = ZONE_CENTERS[0];
            int minDist = Integer.MAX_VALUE;
            
            for (int zoneCenter : ZONE_CENTERS) {
                int dist = graphAnalyzer.calculateShortestPath(node, zoneCenter);
                if (dist >= 0 && dist < minDist) {
                    minDist = dist;
                    closestZone = zoneCenter;
                }
            }
            
            zoneAssignment.put(node, closestZone);
        }
    }
    
    /**
     * Get the safety score for a node (precomputed).
     */
    public double getSafetyScore(int node) {
        return safetyMap.getOrDefault(node, 0.0);
    }
    
    /**
     * Get normalized safety score (0-1).
     */
    public double getNormalizedSafety(int node) {
        if (safetyMap.isEmpty()) return 0;
        double maxSafety = safetyMap.values().stream().mapToDouble(d -> d).max().orElse(1.0);
        return safetyMap.getOrDefault(node, 0.0) / maxSafety;
    }
    
    /**
     * Calculate strategic positioning bonus based on game phase.
     * 
     * Early game (0-30%): Establish position in safe zone
     * Mid game (30-70%): Stay in safe zones, avoid zone crossings
     * Late game (70-100%): Maximize immediate survival, zones less important
     */
    public double calculateZoneBonus(int currentLocation, int destination, double gameProgress) {
        double bonus = 0;
        
        // Base safety bonus (always applies)
        double destSafety = getNormalizedSafety(destination);
        bonus += destSafety * 15.0; // Scale to match other scoring components
        
        // Phase-specific bonuses
        if (gameProgress < 0.3) {
            // Early game: Establish position in safe zone
            bonus += destSafety * 10.0; // Extra bonus for reaching safe zones
            
        } else if (gameProgress < 0.7) {
            // Mid game: Stay in safe zones, penalize zone crossings
            int currentZone = zoneAssignment.getOrDefault(currentLocation, -1);
            int destZone = zoneAssignment.getOrDefault(destination, -1);
            
            if (currentZone != destZone) {
                // Zone crossing - risky, should use SECRET ticket
                bonus -= 20.0; // Penalty for crossing zones
            } else {
                // Staying in zone - good
                bonus += 8.0;
            }
            
        } else {
            // Late game: Immediate survival matters most, zones less important
            bonus += destSafety * 5.0; // Reduced weight
        }
        
        return bonus;
    }
    
    /**
     * Check if a move crosses between zones (high-risk move).
     */
    public boolean isZoneCrossing(int from, int to) {
        int fromZone = zoneAssignment.getOrDefault(from, -1);
        int toZone = zoneAssignment.getOrDefault(to, -1);
        return fromZone != toZone && fromZone != -1 && toZone != -1;
    }
    
    /**
     * Get the safest zone center given detective positions.
     */
    public int getSafestZoneCenter(Set<Integer> detectiveLocations) {
        int safestZone = ZONE_CENTERS[0];
        double bestScore = Double.NEGATIVE_INFINITY;
        
        for (int zoneCenter : ZONE_CENTERS) {
            // Score = safety + distance from detectives
            double score = getSafetyScore(zoneCenter);
            
            int minDetDist = Integer.MAX_VALUE;
            for (int detLoc : detectiveLocations) {
                int dist = graphAnalyzer.calculateShortestPath(zoneCenter, detLoc);
                if (dist >= 0) {
                    minDetDist = Math.min(minDetDist, dist);
                }
            }
            
            score += minDetDist * 10.0;
            
            if (score > bestScore) {
                bestScore = score;
                safestZone = zoneCenter;
            }
        }
        
        return safestZone;
    }
    
    /**
     * Calculate centrality - how "central" a node is on the board.
     * Higher = more central = safer (not in a corner).
     */
    private double calculateCentrality(int node) {
        // Sample 20 random nodes and calculate average distance
        Random rand = new Random(node); // Deterministic for caching
        double totalDist = 0;
        int samples = Math.min(20, totalNodes);
        
        for (int i = 0; i < samples; i++) {
            int target = rand.nextInt(totalNodes) + 1;
            int dist = graphAnalyzer.calculateShortestPath(node, target);
            if (dist >= 0) {
                totalDist += dist;
            }
        }
        
        double avgDist = totalDist / samples;
        // Normalize: typical avg distance is 5-8, we want 0-10 scale
        return Math.min(avgDist, 10.0);
    }
    
    /**
     * Count nodes reachable within N moves.
     */
    private int getReachableCount(int start, int maxMoves) {
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
        
        return reachable.size();
    }
    
    /**
     * Get top N safest nodes on the board.
     */
    public List<Integer> getTopSafeNodes(int count) {
        return safetyMap.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .limit(count)
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toList());
    }
}

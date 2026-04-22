package uk.ac.bris.cs.scotlandyard.ai;

import uk.ac.bris.cs.scotlandyard.model.Colour;
import uk.ac.bris.cs.scotlandyard.model.Transport;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Lightweight, deterministic detective simulation engine for MCTS rollouts.
 * Provides fast, stateless simulation of detective strategies without the overhead
 * of full detective AI classes.
 *
 * v2: simulateCoordinatedMoves now assigns INTERCEPTOR / ENCIRCLER / CHASER roles
 * that mirror CoordinatedDetectiveAI, closing the gap between what MCTS thinks will
 * happen and what actually happens.
 */
public class DetectiveSimulator {
    private final GraphAnalyzer graphAnalyzer;

    // Configuration constants
    private static final int HUB_SEARCH_RADIUS = 5;
    private static final int MAX_HUB_DISTANCE = 8;
    private static final double HUB_COVERAGE_THRESHOLD = 2.0;

    public DetectiveSimulator(GraphAnalyzer graphAnalyzer) {
        this.graphAnalyzer = graphAnalyzer;
    }

    // =========================================================================
    // PRIMARY ENTRY POINT — used by MoveEvaluator.rollout()
    // =========================================================================

    /**
     * Simulate one round of coordinated detective movement.
     *
     * Role assignment (mirrors CoordinatedDetectiveAI):
     *   INTERCEPTOR (1) — closest to top predicted position
     *   BLOCKER     (1) — closest to nearest unreserved hub adjacent to candidate set
     *   ENCIRCLER   (1) — maximizes uncovered perimeter coverage
     *   CHASER      (1) — closest to centroid
     *   FLANKER     (1) — remaining detective
     *
     * Special case: if candidate set < 5, all detectives become CHASER.
     *
     * @param detectivePositions ordered list of current detective locations
     * @param mrXLocation        Mr X's actual position in this rollout
     * @param mrXCandidates      belief-state candidate set (what detectives know)
     * @return new detective positions after one simulated round
     */
    public List<Integer> simulateCoordinatedMoves(List<Integer> detectivePositions,
                                                   int mrXLocation,
                                                   Set<Integer> mrXCandidates) {
        List<Integer> newPositions = new ArrayList<>();
        Set<Integer> occupied = new HashSet<>();
        Set<Integer> reservedHubs = new HashSet<>();

        // Small candidate set case: all detectives chase centroid
        if (mrXCandidates.size() < 5) {
            int centroid = calculateCentroid(mrXCandidates);
            for (int currentPos : detectivePositions) {
                int newPos = moveToward(currentPos, centroid);
                // Avoid collisions
                while (occupied.contains(newPos) && newPos != currentPos) {
                    // Try adjacent positions
                    Set<Integer> adjacent = graphAnalyzer.getAdjacentLocations(currentPos);
                    boolean found = false;
                    for (int adj : adjacent) {
                        if (!occupied.contains(adj)) {
                            newPos = adj;
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        newPos = currentPos; // Stay in place
                        break;
                    }
                }
                occupied.add(newPos);
                newPositions.add(newPos);
            }
            return newPositions;
        }

        // Normal case: assign roles by priority
        List<Integer> remaining = new ArrayList<>(detectivePositions);
        Map<Integer, Integer> roleAssignments = new HashMap<>(); // detective index -> new position

        // Predicted positions must use actual detective locations for safety scores
        // (was incorrectly passed `occupied`, which starts empty — made INTERCEPTOR targets meaningless).
        Set<Integer> detLocsForPrediction = new HashSet<>(detectivePositions);

        // 1. INTERCEPTOR: closest to top predicted position
        List<Integer> topPredicted = computeTopPredictedPositions(mrXCandidates, detLocsForPrediction);
        if (!topPredicted.isEmpty()) {
            int topTarget = topPredicted.get(0);
            int interceptorIdx = findClosestDetective(remaining, topTarget);
            int interceptorPos = remaining.get(interceptorIdx);
            int newPos = moveToward(interceptorPos, topTarget);
            roleAssignments.put(detectivePositions.indexOf(interceptorPos), newPos);
            occupied.add(newPos);
            remaining.remove(interceptorIdx);
        }

        // 2. BLOCKER: closest to nearest unreserved hub adjacent to candidate set
        int hubTarget = findNearestUnreservedHubAdjacentTo(mrXCandidates, reservedHubs);
        if (hubTarget != -1 && !remaining.isEmpty()) {
            int blockerIdx = findClosestDetective(remaining, hubTarget);
            int blockerPos = remaining.get(blockerIdx);
            int newPos = moveToward(blockerPos, hubTarget);
            roleAssignments.put(detectivePositions.indexOf(blockerPos), newPos);
            occupied.add(newPos);
            reservedHubs.add(hubTarget);
            remaining.remove(blockerIdx);
        }

        // 3. ENCIRCLER: maximizes uncovered perimeter coverage
        if (!remaining.isEmpty()) {
            Set<Integer> perimeter = getPerimeterNodes(mrXCandidates);
            // Match CoordinationState: coverage from teammates' CURRENT positions (not planned moves).
            Set<Integer> coveredByUnassigned = nodesWithinGraphDistance2(remaining);
            int encirclerIdx = findBestEncircler(remaining, perimeter, coveredByUnassigned);
            int encirclerPos = remaining.get(encirclerIdx);
            // Avoid perimeter cells already covered by teammates or reserved by earlier roles.
            Set<Integer> perimeterExclusion = new HashSet<>(coveredByUnassigned);
            perimeterExclusion.addAll(occupied);
            int nearestPerimeter = findNearestUncovered(encirclerPos, perimeter, perimeterExclusion);
            int newPos = nearestPerimeter != -1 ? moveToward(encirclerPos, nearestPerimeter) : encirclerPos;
            roleAssignments.put(detectivePositions.indexOf(encirclerPos), newPos);
            occupied.add(newPos);
            remaining.remove(encirclerIdx);
        }

        // 4. CHASER: closest to centroid
        if (!remaining.isEmpty()) {
            int centroid = calculateCentroid(mrXCandidates);
            int chaserIdx = findClosestDetective(remaining, centroid);
            int chaserPos = remaining.get(chaserIdx);
            int newPos = moveToward(chaserPos, centroid);
            roleAssignments.put(detectivePositions.indexOf(chaserPos), newPos);
            occupied.add(newPos);
            remaining.remove(chaserIdx);
        }

        // 5. FLANKER: remaining detective - avoid distance ≤2 from planned destinations
        if (!remaining.isEmpty()) {
            int flankerPos = remaining.get(0);
            // Find nodes reachable from candidates, not within distance 2 of occupied
            Set<Integer> reachableFromCandidates = new HashSet<>();
            for (int candidate : mrXCandidates) {
                reachableFromCandidates.addAll(graphAnalyzer.getAdjacentLocations(candidate));
            }
            
            // Build avoid zone (distance ≤ 2 from occupied)
            Set<Integer> avoidZone = new HashSet<>();
            for (int dest : occupied) {
                avoidZone.add(dest);
                for (int neighbor1 : graphAnalyzer.getAdjacentLocations(dest)) {
                    avoidZone.add(neighbor1);
                    for (int neighbor2 : graphAnalyzer.getAdjacentLocations(neighbor1)) {
                        avoidZone.add(neighbor2);
                    }
                }
            }
            
            reachableFromCandidates.removeAll(avoidZone);
            
            int newPos;
            if (!reachableFromCandidates.isEmpty()) {
                int nearest = findNearestNode(flankerPos, reachableFromCandidates);
                newPos = moveToward(flankerPos, nearest);
            } else {
                // Fallback: move toward centroid
                int centroid = calculateCentroid(mrXCandidates);
                newPos = moveToward(flankerPos, centroid);
            }
            
            roleAssignments.put(detectivePositions.indexOf(flankerPos), newPos);
            occupied.add(newPos);
        }

        // Build final positions list in original order
        for (int i = 0; i < detectivePositions.size(); i++) {
            if (roleAssignments.containsKey(i)) {
                newPositions.add(roleAssignments.get(i));
            } else {
                // Shouldn't happen, but fallback to current position
                newPositions.add(detectivePositions.get(i));
            }
        }

        return newPositions;
    }

    // =========================================================================
    // EXISTING PUBLIC METHODS (unchanged — other callers depend on these)
    // =========================================================================

    /**
     * Simulate a hub-blocking detective move.
     */
    public int simulateHubBlockingMove(int detLoc, int mrXLoc, Set<Integer> otherDets) {
        List<Integer> nearbyHubs = findNearbyHubs(mrXLoc, HUB_SEARCH_RADIUS);
        int bestHub = -1;
        int bestDistance = Integer.MAX_VALUE;

        for (int hub : nearbyHubs) {
            if (isHubCoveredByOthers(hub, otherDets, detLoc)) continue;
            int distToHub = graphAnalyzer.calculateShortestPath(detLoc, hub);
            if (distToHub >= 0 && distToHub < bestDistance) {
                bestDistance = distToHub;
                bestHub = hub;
            }
        }

        if (bestHub != -1) return moveToward(detLoc, bestHub);
        return moveToward(detLoc, mrXLoc);
    }

    /**
     * Simulate a candidate-dividing detective move.
     */
    public int simulateCandidateDividingMove(int detLoc, Set<Integer> mrXCandidates,
                                              Set<Integer> otherDets) {
        Set<Integer> adjacent = graphAnalyzer.getAdjacentLocations(detLoc);
        int bestDest = detLoc;
        int bestCoverage = 0;

        for (int adj : adjacent) {
            if (otherDets.contains(adj)) continue;
            int coverage = countCoveredCandidates(adj, mrXCandidates);
            if (coverage > bestCoverage) {
                bestCoverage = coverage;
                bestDest = adj;
            }
        }

        if (bestDest == detLoc && !mrXCandidates.isEmpty()) {
            int centroid = calculateCentroid(mrXCandidates);
            bestDest = moveToward(detLoc, centroid);
        }
        return bestDest;
    }

    /**
     * Hybrid detective move (hub-blocking + candidate-dividing).
     */
    public int simulateHybridMove(int detLoc, int mrXLoc, Set<Integer> mrXCandidates,
                                   Set<Integer> otherDets, double hubWeight) {
        int hubMove = simulateHubBlockingMove(detLoc, mrXLoc, otherDets);
        int candidateMove = simulateCandidateDividingMove(detLoc, mrXCandidates, otherDets);

        if (hubMove == candidateMove) return hubMove;
        if (mrXCandidates.size() > 30) return candidateMove;
        int distToMrX = graphAnalyzer.calculateShortestPath(detLoc, mrXLoc);
        if (distToMrX >= 0 && distToMrX <= 3) return hubMove;
        return (Math.random() < hubWeight) ? hubMove : candidateMove;
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    private List<Integer> findNearbyHubs(int targetLoc, int maxDistance) {
        Set<Integer> allHubs = graphAnalyzer.getTransportHubs();
        List<int[]> hubsWithDist = new ArrayList<>();
        for (int hub : allHubs) {
            int dist = graphAnalyzer.calculateShortestPath(hub, targetLoc);
            if (dist >= 0 && dist <= maxDistance) {
                hubsWithDist.add(new int[]{hub, dist});
            }
        }
        hubsWithDist.sort(Comparator.comparingInt(a -> a[1]));
        List<Integer> result = new ArrayList<>();
        for (int[] hd : hubsWithDist) result.add(hd[0]);
        return result;
    }

    private boolean isHubCoveredByOthers(int hub, Collection<Integer> otherDets, int myLoc) {
        for (int otherLoc : otherDets) {
            if (otherLoc == myLoc) continue;
            int dist = graphAnalyzer.calculateShortestPath(otherLoc, hub);
            if (dist >= 0 && dist <= HUB_COVERAGE_THRESHOLD) return true;
        }
        return false;
    }

    private int moveToward(int from, int target) {
        if (from == target) return from;
        Set<Integer> adjacent = graphAnalyzer.getAdjacentLocations(from);
        int bestMove = from;
        int bestDist = Integer.MAX_VALUE;
        for (int adj : adjacent) {
            int dist = graphAnalyzer.calculateShortestPath(adj, target);
            if (dist >= 0 && dist < bestDist) {
                bestDist = dist;
                bestMove = adj;
            }
        }
        return bestMove;
    }

    private int countCoveredCandidates(int position, Set<Integer> candidates) {
        int count = 0;
        int checked = 0;
        int maxCheck = Math.min(candidates.size(), 20);
        for (int candidate : candidates) {
            if (checked >= maxCheck) break;
            checked++;
            int dist = graphAnalyzer.calculateShortestPath(position, candidate);
            if (dist >= 0 && dist <= 2) count++;
        }
        if (candidates.size() > maxCheck) {
            count = (int) Math.round((double) count * candidates.size() / maxCheck);
        }
        return count;
    }

    /**
     * Fast approximate centroid: for small sets exact, for large sets sampled.
     */
    private int calculateCentroid(Set<Integer> locations) {
        if (locations.isEmpty()) return -1;
        if (locations.size() == 1) return locations.iterator().next();

        if (locations.size() <= 5) {
            int bestCentroid = -1;
            int bestTotalDist = Integer.MAX_VALUE;
            for (int candidate : locations) {
                int totalDist = 0;
                for (int other : locations) {
                    if (candidate == other) continue;
                    int dist = graphAnalyzer.calculateShortestPath(candidate, other);
                    if (dist < 0) { totalDist = Integer.MAX_VALUE; break; }
                    totalDist += dist;
                }
                if (totalDist < bestTotalDist) {
                    bestTotalDist = totalDist;
                    bestCentroid = candidate;
                }
            }
            return bestCentroid;
        }

        // Large set: sample 3 reference points, score up to 10 candidates
        List<Integer> locList = new ArrayList<>(locations);
        int[] sampleIndices = {0, locList.size() / 2, locList.size() - 1};

        int bestCentroid = locList.get(0);
        int bestTotalDist = Integer.MAX_VALUE;
        int checkLimit = Math.min(locList.size(), 10);

        for (int i = 0; i < checkLimit; i++) {
            int candidate = locList.get(i);
            int totalDist = 0;
            for (int si : sampleIndices) {
                int dist = graphAnalyzer.calculateShortestPath(candidate, locList.get(si));
                if (dist < 0) { totalDist = Integer.MAX_VALUE; break; }
                totalDist += dist;
            }
            if (totalDist < bestTotalDist) {
                bestTotalDist = totalDist;
                bestCentroid = candidate;
            }
        }

        return bestCentroid;
    }

    /**
     * Compute top predicted positions for Mr. X based on reachability and safety.
     * Simplified version for simulation (no transport mode filtering).
     */
    private List<Integer> computeTopPredictedPositions(Set<Integer> candidates, Set<Integer> detectiveLocations) {
        Map<Integer, Double> weights = new HashMap<>();
        
        // Build reachability map
        Map<Integer, Integer> reachabilityCount = new HashMap<>();
        for (int candidate : candidates) {
            for (int neighbor : graphAnalyzer.getAdjacentLocations(candidate)) {
                reachabilityCount.merge(neighbor, 1, Integer::sum);
            }
        }
        
        // Compute weights: reachabilityCount × safetyScore
        for (Map.Entry<Integer, Integer> entry : reachabilityCount.entrySet()) {
            int node = entry.getKey();
            double safetyScore = graphAnalyzer.calculateSafetyScore(node, detectiveLocations);
            double clampedSafety = Math.max(0.0, safetyScore);
            double weight = entry.getValue() * clampedSafety;
            weights.put(node, weight);
        }
        
        // Return top 3 by weight
        return weights.entrySet().stream()
            .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
            .limit(3)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    /**
     * Find closest detective to a target location.
     */
    private int findClosestDetective(List<Integer> detectivePositions, int target) {
        if (detectivePositions == null || detectivePositions.isEmpty()) {
            return 0;
        }
        int closestIdx = 0;
        int minDistance = Integer.MAX_VALUE;
        boolean any = false;

        for (int i = 0; i < detectivePositions.size(); i++) {
            int dist = graphAnalyzer.calculateShortestPath(detectivePositions.get(i), target);
            if (dist < 0) {
                dist = Integer.MAX_VALUE;
            }
            // Tie-break: lower index (caller should pass detectives sorted by Colour.name())
            if (!any || dist < minDistance || (dist == minDistance && i < closestIdx)) {
                minDistance = dist;
                closestIdx = i;
                any = true;
            }
        }

        return closestIdx;
    }

    /**
     * Find nearest unreserved hub adjacent to candidate set.
     */
    private int findNearestUnreservedHubAdjacentTo(Set<Integer> candidateSet, Set<Integer> reservedHubs) {
        Set<Integer> allHubs = graphAnalyzer.getTransportHubs();
        Set<Integer> adjacentHubs = new HashSet<>();
        
        // Find hubs adjacent to candidate set
        for (int candidate : candidateSet) {
            for (int neighbor : graphAnalyzer.getAdjacentLocations(candidate)) {
                if (allHubs.contains(neighbor) && !reservedHubs.contains(neighbor)) {
                    adjacentHubs.add(neighbor);
                }
            }
        }
        
        if (adjacentHubs.isEmpty()) {
            // Fallback: find nearest unreserved hub anywhere
            int nearestHub = -1;
            int minDist = Integer.MAX_VALUE;
            
            for (int hub : allHubs) {
                if (!reservedHubs.contains(hub)) {
                    int totalDist = 0;
                    int count = 0;
                    for (int candidate : candidateSet) {
                        int d = graphAnalyzer.calculateShortestPath(hub, candidate);
                        if (d >= 0) {
                            totalDist += d;
                            count++;
                        }
                        if (count >= 10) break; // Sample for performance
                    }
                    if (count > 0 && totalDist < minDist) {
                        minDist = totalDist;
                        nearestHub = hub;
                    }
                }
            }
            return nearestHub;
        }
        
        // Return nearest adjacent hub (minimum average distance to candidate set)
        int bestHub = -1;
        int minAvgDist = Integer.MAX_VALUE;
        
        for (int hub : adjacentHubs) {
            int totalDist = 0;
            int count = 0;
            for (int candidate : candidateSet) {
                int d = graphAnalyzer.calculateShortestPath(hub, candidate);
                if (d >= 0) {
                    totalDist += d;
                    count++;
                }
                if (count >= 10) break; // Sample for performance
            }
            if (count > 0 && totalDist < minAvgDist) {
                minAvgDist = totalDist;
                bestHub = hub;
            }
        }
        
        return bestHub;
    }

    /**
     * Get perimeter nodes: nodes adjacent to candidate set but not in candidate set.
     */
    private Set<Integer> getPerimeterNodes(Set<Integer> candidateSet) {
        Set<Integer> perimeter = new HashSet<>();
        for (int candidate : candidateSet) {
            for (int neighbor : graphAnalyzer.getAdjacentLocations(candidate)) {
                if (!candidateSet.contains(neighbor)) {
                    perimeter.add(neighbor);
                }
            }
        }
        return perimeter;
    }

    /**
     * Find detective that maximizes uncovered perimeter coverage.
     */
    private int findBestEncircler(List<Integer> detectivePositions, Set<Integer> perimeter, Set<Integer> coveredByOthers) {
        int bestIdx = 0;
        int maxCoverage = -1;

        for (int i = 0; i < detectivePositions.size(); i++) {
            int pos = detectivePositions.get(i);

            int coverage = 0;
            Set<Integer> reachable = new HashSet<>();
            reachable.add(pos);
            for (int neighbor : graphAnalyzer.getAdjacentLocations(pos)) {
                reachable.add(neighbor);
                for (int neighbor2 : graphAnalyzer.getAdjacentLocations(neighbor)) {
                    reachable.add(neighbor2);
                }
            }

            for (int node : perimeter) {
                if (reachable.contains(node) && !coveredByOthers.contains(node)) {
                    coverage++;
                }
            }

            // First max wins => lowest index on ties (matches sorted Colour.name() order)
            if (coverage > maxCoverage) {
                maxCoverage = coverage;
                bestIdx = i;
            }
        }

        return bestIdx;
    }

    /** Nodes within graph distance ≤2 of any seed (same footprint as CoordinationState.getCoveredNodes). */
    private Set<Integer> nodesWithinGraphDistance2(Collection<Integer> seeds) {
        Set<Integer> covered = new HashSet<>();
        for (int loc : seeds) {
            covered.add(loc);
            for (int n1 : graphAnalyzer.getAdjacentLocations(loc)) {
                covered.add(n1);
                for (int n2 : graphAnalyzer.getAdjacentLocations(n1)) {
                    covered.add(n2);
                }
            }
        }
        return covered;
    }

    /**
     * Find nearest uncovered node from a set.
     */
    private int findNearestUncovered(int from, Set<Integer> nodes, Set<Integer> occupied) {
        int nearest = -1;
        int minDist = Integer.MAX_VALUE;
        
        for (int node : nodes) {
            if (!occupied.contains(node)) {
                int dist = graphAnalyzer.calculateShortestPath(from, node);
                if (dist >= 0 && dist < minDist) {
                    minDist = dist;
                    nearest = node;
                }
            }
        }
        
        return nearest;
    }

    /**
     * Find nearest node from a set.
     */
    private int findNearestNode(int from, Set<Integer> nodes) {
        int nearest = -1;
        int minDist = Integer.MAX_VALUE;
        
        for (int node : nodes) {
            int dist = graphAnalyzer.calculateShortestPath(from, node);
            if (dist >= 0 && dist < minDist) {
                minDist = dist;
                nearest = node;
            }
        }
        
        return nearest != -1 ? nearest : from;
    }
}

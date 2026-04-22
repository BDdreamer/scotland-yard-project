package uk.ac.bris.cs.scotlandyard.ai;

import uk.ac.bris.cs.scotlandyard.model.Colour;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Thread-safe coordination state shared by all CoordinatedDetectiveAI instances.
 * Manages role assignments, zone partitions, hub reservations, and planned destinations
 * for the two-phase move coordination protocol.
 */
public class CoordinationState {

    // Synchronized on `this` for all mutations
    private final Map<Colour, Role> roleAssignments = new HashMap<>();
    private final Map<Colour, Set<Integer>> zoneAssignments = new HashMap<>();
    private final Set<Integer> reservedHubs = new HashSet<>();
    private final Map<Colour, Integer> plannedDestinations = new HashMap<>();

    /**
     * Role enum defining the five detective roles in the coordination protocol.
     */
    public enum Role {
        INTERCEPTOR,  // move toward top predicted position
        BLOCKER,      // occupy/approach hub adjacent to candidate set
        ENCIRCLER,    // close off perimeter escape routes
        CHASER,       // move toward centroid of candidate set
        FLANKER       // cut off lateral escapes not covered by others
    }

    /**
     * Phase 1: Register candidate destination before penalty is applied.
     * 
     * @param colour the detective colour
     * @param destination the planned destination node
     */
    public synchronized void registerPlannedDestination(Colour colour, int destination) {
        plannedDestinations.put(colour, destination);
    }

    /**
     * Phase 2 query: Is this destination already claimed by another detective?
     * 
     * @param colour the detective colour making the query
     * @param destination the destination node to check
     * @return true if another detective has claimed this destination
     */
    public synchronized boolean isDestinationClaimed(Colour colour, int destination) {
        for (Map.Entry<Colour, Integer> entry : plannedDestinations.entrySet()) {
            if (!entry.getKey().equals(colour) && entry.getValue() == destination) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get all planned destinations except for the specified detective.
     * 
     * @param colour the detective colour to exclude
     * @return set of destinations planned by other detectives
     */
    public synchronized Set<Integer> getOtherPlannedDestinations(Colour colour) {
        Set<Integer> others = new HashSet<>();
        for (Map.Entry<Colour, Integer> entry : plannedDestinations.entrySet()) {
            if (!entry.getKey().equals(colour)) {
                others.add(entry.getValue());
            }
        }
        return others;
    }

    /**
     * Get the role assigned to a detective.
     * 
     * @param colour the detective colour
     * @return the assigned role, or null if not yet assigned
     */
    public synchronized Role getRole(Colour colour) {
        return roleAssignments.get(colour);
    }

    /**
     * Get the zone (candidate partition) assigned to a detective.
     * 
     * @param colour the detective colour
     * @return the assigned zone, or empty set if not yet assigned
     */
    public synchronized Set<Integer> getZone(Colour colour) {
        return zoneAssignments.getOrDefault(colour, new HashSet<>());
    }

    /**
     * Reserve a hub for the Blocker role.
     * 
     * @param hub the hub node to reserve
     */
    public synchronized void reserveHub(int hub) {
        reservedHubs.add(hub);
    }

    /**
     * Check if a hub is already reserved.
     * 
     * @param hub the hub node to check
     * @return true if the hub is reserved
     */
    public synchronized boolean isHubReserved(int hub) {
        return reservedHubs.contains(hub);
    }

    /**
     * Called by RED detective's onRotationComplete.
     * Clears all state for the next rotation.
     */
    public synchronized void onRotationComplete() {
        plannedDestinations.clear();
        reservedHubs.clear();
        roleAssignments.clear();
        zoneAssignments.clear();
    }

    /**
     * Assign roles and zones to all detectives for this rotation.
     * This method is idempotent — if roles are already assigned (roleAssignments.size() == 5),
     * it returns immediately without reassigning.
     * 
     * @param detectives sorted list of detective colours
     * @param candidateSet current Mr X candidate set
     * @param topPredictedPositions top predicted positions for Mr X
     * @param detectiveLocations current detective locations (map: Colour -> location)
     * @param graphAnalyzer graph analyzer for distance calculations
     */
    public synchronized void assignRolesAndZones(
            List<Colour> detectives,
            Set<Integer> candidateSet,
            List<Integer> topPredictedPositions,
            Map<Colour, Integer> detectiveLocations,
            GraphAnalyzer graphAnalyzer) {
        
        // Idempotency guard: if roles already assigned this rotation, return immediately
        if (roleAssignments.size() == 5) {
            return;  // Already assigned this rotation
        }

        // Small candidate set case: assign all detectives as CHASER
        if (candidateSet.size() < 5) {
            // Still partition zones even for small candidate sets
            partitionZones(detectives, candidateSet);
            for (Colour detective : detectives) {
                roleAssignments.put(detective, Role.CHASER);
            }
            return;
        }

        // Partition zones (for normal case with candidateSet.size() >= 5)
        partitionZones(detectives, candidateSet);

        // Normal case: assign roles by priority
        List<Colour> remaining = new ArrayList<>(detectives);

        // 1. INTERCEPTOR: closest to topPredicted[0]
        if (!topPredictedPositions.isEmpty()) {
            int topTarget = topPredictedPositions.get(0);
            Colour interceptor = findClosestDetective(remaining, topTarget, detectiveLocations, graphAnalyzer);
            roleAssignments.put(interceptor, Role.INTERCEPTOR);
            remaining.remove(interceptor);
        }

        // 2. BLOCKER: closest to nearest unreserved hub adjacent to candidateSet
        int hubTarget = findNearestUnreservedHubAdjacentTo(candidateSet, graphAnalyzer);
        if (hubTarget != -1) {
            Colour blocker = findClosestDetective(remaining, hubTarget, detectiveLocations, graphAnalyzer);
            roleAssignments.put(blocker, Role.BLOCKER);
            reserveHub(hubTarget);
            remaining.remove(blocker);
        }

        // 3. ENCIRCLER: maximises uncovered perimeter coverage
        Set<Integer> perimeterNodes = getPerimeterNodes(candidateSet, graphAnalyzer);
        Set<Integer> coveredByOthers = getCoveredNodes(remaining, detectiveLocations, graphAnalyzer);
        Colour encircler = findBestEncircler(remaining, perimeterNodes, coveredByOthers, detectiveLocations, graphAnalyzer);
        roleAssignments.put(encircler, Role.ENCIRCLER);
        remaining.remove(encircler);

        // 4. CHASER: closest to centroid
        int centroid = computeCentroid(candidateSet, graphAnalyzer);
        Colour chaser = findClosestDetective(remaining, centroid, detectiveLocations, graphAnalyzer);
        roleAssignments.put(chaser, Role.CHASER);
        remaining.remove(chaser);

        // 5. FLANKER: the last remaining detective
        if (!remaining.isEmpty()) {
            roleAssignments.put(remaining.get(0), Role.FLANKER);
        }
    }

    /**
     * Partition candidate set into zones for each detective.
     * Mirrors CandidateDividingDetective.partitionCandidates logic.
     */
    private void partitionZones(List<Colour> detectives, Set<Integer> candidateSet) {
        List<Integer> sorted = new ArrayList<>(candidateSet);
        Collections.sort(sorted);

        int partitionSize = (int) Math.ceil((double) sorted.size() / detectives.size());

        for (int i = 0; i < detectives.size(); i++) {
            int from = i * partitionSize;
            // Ensure 'from' doesn't exceed the list size
            if (from >= sorted.size()) {
                // Assign empty zone if we've run out of nodes
                zoneAssignments.put(detectives.get(i), new HashSet<>());
                continue;
            }
            int to = Math.min(from + partitionSize, sorted.size());
            Set<Integer> zone = new HashSet<>(sorted.subList(from, to));
            zoneAssignments.put(detectives.get(i), zone);
        }
    }

    /**
     * Find the detective closest to a target location.
     * Tie-break by lexicographic order of Colour.name().
     */
    private Colour findClosestDetective(
            List<Colour> candidates,
            int target,
            Map<Colour, Integer> detectiveLocations,
            GraphAnalyzer graphAnalyzer) {
        
        Colour closest = null;
        int minDistance = Integer.MAX_VALUE;

        for (Colour detective : candidates) {
            Integer location = detectiveLocations.get(detective);
            if (location == null) continue;

            int distance = graphAnalyzer.calculateShortestPath(location, target);
            if (distance == -1) distance = Integer.MAX_VALUE;

            // Tie-break by lexicographic order
            if (distance < minDistance || 
                (distance == minDistance && (closest == null || detective.name().compareTo(closest.name()) < 0))) {
                minDistance = distance;
                closest = detective;
            }
        }

        return closest != null ? closest : candidates.get(0);
    }

    /**
     * Find nearest unreserved hub adjacent to candidate set.
     * Returns -1 if no hubs found.
     */
    private int findNearestUnreservedHubAdjacentTo(Set<Integer> candidateSet, GraphAnalyzer graphAnalyzer) {
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
            // Fallback: find NEAREST unreserved hub anywhere on board
            // Use minimum average distance to candidate set
            int nearestHub = -1;
            int minDist = Integer.MAX_VALUE;
            
            for (int hub : allHubs) {
                if (!reservedHubs.contains(hub)) {
                    int totalDist = 0;
                    for (int candidate : candidateSet) {
                        int d = graphAnalyzer.calculateShortestPath(hub, candidate);
                        totalDist += (d == -1 ? 100 : d);
                    }
                    if (totalDist < minDist) {
                        minDist = totalDist;
                        nearestHub = hub;
                    }
                }
            }
            return nearestHub;
        }

        // Return nearest adjacent hub (minimum average distance to candidate set)
        return adjacentHubs.stream()
            .min(java.util.Comparator.comparingInt(hub -> 
                candidateSet.stream()
                    .mapToInt(c -> {
                        int d = graphAnalyzer.calculateShortestPath(hub, c);
                        return d == -1 ? 100 : d;
                    }).sum()))
            .orElse(-1);
    }

    /**
     * Get perimeter nodes: nodes adjacent to candidate set but not in candidate set.
     */
    private Set<Integer> getPerimeterNodes(Set<Integer> candidateSet, GraphAnalyzer graphAnalyzer) {
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
     * Get nodes covered (within distance 2) by any detective in the list.
     */
    private Set<Integer> getCoveredNodes(
            List<Colour> detectives,
            Map<Colour, Integer> detectiveLocations,
            GraphAnalyzer graphAnalyzer) {
        
        Set<Integer> covered = new HashSet<>();
        for (Colour detective : detectives) {
            Integer location = detectiveLocations.get(detective);
            if (location == null) continue;

            covered.add(location);
            for (int neighbor : graphAnalyzer.getAdjacentLocations(location)) {
                covered.add(neighbor);
                for (int neighbor2 : graphAnalyzer.getAdjacentLocations(neighbor)) {
                    covered.add(neighbor2);
                }
            }
        }
        return covered;
    }

    /**
     * Find detective that maximises uncovered perimeter coverage.
     */
    private Colour findBestEncircler(
            List<Colour> candidates,
            Set<Integer> perimeterNodes,
            Set<Integer> coveredByOthers,
            Map<Colour, Integer> detectiveLocations,
            GraphAnalyzer graphAnalyzer) {
        
        Colour best = null;
        int maxCoverage = -1;

        for (Colour detective : candidates) {
            Integer location = detectiveLocations.get(detective);
            if (location == null) continue;

            // Count perimeter nodes within distance 2 NOT already covered by others
            int coverage = 0;
            Set<Integer> reachable = new HashSet<>();
            reachable.add(location);
            for (int neighbor : graphAnalyzer.getAdjacentLocations(location)) {
                reachable.add(neighbor);
                for (int neighbor2 : graphAnalyzer.getAdjacentLocations(neighbor)) {
                    reachable.add(neighbor2);
                }
            }

            for (int node : perimeterNodes) {
                if (reachable.contains(node) && !coveredByOthers.contains(node)) {
                    coverage++;
                }
            }

            // Tie-break by lexicographic order
            if (coverage > maxCoverage || 
                (coverage == maxCoverage && (best == null || detective.name().compareTo(best.name()) < 0))) {
                maxCoverage = coverage;
                best = detective;
            }
        }

        return best != null ? best : candidates.get(0);
    }

    /**
     * Compute centroid of candidate set as the medoid (node with minimum sum of distances).
     * Samples up to 50 nodes for performance.
     */
    private int computeCentroid(Set<Integer> candidateSet, GraphAnalyzer graphAnalyzer) {
        if (candidateSet.isEmpty()) return -1;
        if (candidateSet.size() == 1) return candidateSet.iterator().next();

        // Sample for performance
        List<Integer> sampled = new ArrayList<>(candidateSet);
        if (sampled.size() > 50) {
            Collections.shuffle(sampled);
            sampled = sampled.subList(0, 50);
        }

        int bestNode = -1;
        int minTotalDistance = Integer.MAX_VALUE;
        Set<Integer> sampledSet = new HashSet<>(sampled);

        for (int candidate : sampled) {
            int totalDistance = 0;
            for (int other : sampledSet) {
                if (candidate == other) continue;
                int dist = graphAnalyzer.calculateShortestPath(candidate, other);
                totalDistance += (dist == -1 ? 1000 : dist);
            }

            if (totalDistance < minTotalDistance) {
                minTotalDistance = totalDistance;
                bestNode = candidate;
            }
        }

        return bestNode;
    }
}

package uk.ac.bris.cs.scotlandyard.ai;

import uk.ac.bris.cs.gamekit.graph.Graph;
import uk.ac.bris.cs.scotlandyard.model.Colour;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.Player;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYardView;
import uk.ac.bris.cs.scotlandyard.model.Spectator;
import uk.ac.bris.cs.scotlandyard.model.Ticket;
import uk.ac.bris.cs.scotlandyard.model.Transport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Coordinated detective AI implementing the two-phase move coordination protocol.
 * 
 * Phase 1: Each detective registers their planned destination before penalty is applied.
 * Phase 2: Each detective queries the shared state to avoid collisions and applies penalty.
 * 
 * Role assignment is performed once per rotation by the first detective (RED) and is
 * idempotent for the remaining detectives in that rotation.
 */
public class CoordinatedDetectiveAI implements Player, Spectator {

    // ========== Field Declarations ==========
    
    private final Colour colour;
    private final GraphAnalyzer graphAnalyzer;
    private final Graph<Integer, Transport> graph;
    private final CoordinationState coordinationState;
    private final CandidateDividingDetective.Tracker tracker;

    // ========== Constructor ==========
    
    /**
     * Constructor with dependency injection.
     * 
     * @param colour the detective colour
     * @param graphAnalyzer graph analyzer for distance calculations and hub detection
     * @param graph the Scotland Yard graph
     * @param coordinationState shared coordination state for all detectives
     * @param tracker shared belief state tracker (spectator) - reuses CandidateDividingDetective.Tracker
     */
    public CoordinatedDetectiveAI(
            Colour colour,
            GraphAnalyzer graphAnalyzer,
            Graph<Integer, Transport> graph,
            CoordinationState coordinationState,
            CandidateDividingDetective.Tracker tracker) {
        
        this.colour = colour;
        this.graphAnalyzer = graphAnalyzer;
        this.graph = graph;
        this.coordinationState = coordinationState;
        this.tracker = tracker;
    }

    // ========== Spectator Interface Implementation ==========
    
    /**
     * Returns true if this detective is the tracker updater (RED detective).
     * Only RED updates the shared tracker to avoid redundant updates.
     */
    private boolean isTrackerUpdater() {
        return colour == Colour.RED;
    }

    @Override
    public void onMoveMade(ScotlandYardView view, Move move) {
        // Delegate to tracker only if this is the RED detective
        if (isTrackerUpdater()) {
            tracker.onMoveMade(view, move);
        }
    }

    @Override
    public void onRoundStarted(ScotlandYardView view, int round) {
        // Delegate to tracker only if this is the RED detective
        if (isTrackerUpdater()) {
            tracker.onRoundStarted(view, round);
        }
    }

    @Override
    public void onRotationComplete(ScotlandYardView view) {
        // Delegate to tracker only if this is the RED detective
        if (isTrackerUpdater()) {
            tracker.onRotationComplete(view);
            
            // Clear coordination state for next rotation
            coordinationState.onRotationComplete();
        }
    }

    // ========== Player Interface Implementation ==========
    
    @Override
    public void makeMove(ScotlandYardView view, int location, Set<Move> moves, Consumer<Move> callback) {
        // Get current game state
        final Set<Integer> candidates = tracker.snapshotCandidates(view);
        
        // Check if this is a reveal round
        final boolean isRevealRound = view.getRounds().get(view.getCurrentRound() - 1);
        final int revealedPosition;
        if (isRevealRound) {
            revealedPosition = view.getPlayerLocation(Colour.BLACK).orElse(-1);
        } else {
            revealedPosition = -1;
        }
        
        // FIRST: Reveal-round direct capture check (before phases)
        if (isRevealRound && revealedPosition > 0) {
            int distToRevealed = graphAnalyzer.calculateShortestPath(location, revealedPosition);
            if (distToRevealed == 1) {
                // Detective is adjacent to revealed position - try to capture
                List<uk.ac.bris.cs.scotlandyard.model.TicketMove> captureMoves = moves.stream()
                    .filter(m -> m instanceof uk.ac.bris.cs.scotlandyard.model.TicketMove)
                    .map(m -> (uk.ac.bris.cs.scotlandyard.model.TicketMove) m)
                    .filter(m -> m.destination() == revealedPosition)
                    .collect(Collectors.toList());
                
                if (!captureMoves.isEmpty()) {
                    // Prefer TAXI > BUS > UNDERGROUND > SECRET to conserve valuable tickets
                    uk.ac.bris.cs.scotlandyard.model.TicketMove bestCapture = captureMoves.stream()
                        .min((m1, m2) -> {
                            int priority1 = getTicketPriority(m1.ticket());
                            int priority2 = getTicketPriority(m2.ticket());
                            return Integer.compare(priority1, priority2);
                        })
                        .orElse(captureMoves.get(0));
                    
                    callback.accept(bestCapture);
                    return;  // Skip phases 1 and 2
                }
            }
        }
        
        // Use singleton set on reveal rounds
        final Set<Integer> effectiveCandidates = (isRevealRound && revealedPosition > 0)
            ? java.util.Collections.singleton(revealedPosition)
            : candidates;
        
        final boolean isSingleton = effectiveCandidates.size() == 1;
        
        // Sample candidates for performance
        final Set<Integer> sampledCandidates = sampleCandidates(effectiveCandidates);
        
        // Get detective locations as a map
        final Map<Colour, Integer> detectiveLocationMap = getDetectiveLocationMap(view);
        final Set<Integer> detectiveLocations = new HashSet<>(detectiveLocationMap.values());
        
        // Compute predicted positions
        final Ticket inferredMode = getInferredTransportMode();
        final Map<Integer, Double> predictedWeights = computePredictedPositions(
            sampledCandidates, detectiveLocations, inferredMode);
        final List<Integer> topPredicted = getTopPredictedPositions(predictedWeights, 3);
        
        // Assign roles and zones (idempotent - only first detective assigns)
        final List<Colour> sortedDetectives = getSortedDetectives(view);
        coordinationState.assignRolesAndZones(
            sortedDetectives, effectiveCandidates, topPredicted, detectiveLocationMap, graphAnalyzer);
        
        // Get my role and zone
        final CoordinationState.Role myRole = coordinationState.getRole(colour);
        final Set<Integer> myZone = coordinationState.getZone(colour);
        
        // Determine target set
        final Set<Integer> targetSet = (myRole == CoordinationState.Role.INTERCEPTOR || 
                                   myRole == CoordinationState.Role.CHASER)
            ? effectiveCandidates : myZone;
        
        // Get role target
        final int roleTarget = getRoleTarget(myRole, topPredicted, effectiveCandidates, 
                                       detectiveLocations, location);
        
        // Filter to TicketMoves only
        List<uk.ac.bris.cs.scotlandyard.model.TicketMove> ticketMoves = moves.stream()
            .filter(m -> m instanceof uk.ac.bris.cs.scotlandyard.model.TicketMove)
            .map(m -> (uk.ac.bris.cs.scotlandyard.model.TicketMove) m)
            .collect(Collectors.toList());
        
        // If no TicketMoves available, select first available move
        if (ticketMoves.isEmpty()) {
            callback.accept(moves.iterator().next());
            return;
        }
        
        // --- PHASE 1: Score without coordination penalty ---
        final boolean isEncircler = (myRole == CoordinationState.Role.ENCIRCLER);
        
        uk.ac.bris.cs.scotlandyard.model.TicketMove phase1Best = ticketMoves.stream()
            .max(java.util.Comparator.comparingDouble(m -> 
                scoreMove(m.destination(), targetSet, roleTarget, isEncircler,
                         detectiveLocations, java.util.Collections.emptySet(), 
                         false, isRevealRound && revealedPosition > 0, revealedPosition)))
            .orElse(ticketMoves.get(0));
        
        // Register Phase 1 destination
        coordinationState.registerPlannedDestination(colour, phase1Best.destination());
        
        // --- PHASE 2: Score with coordination penalty ---
        final Set<Integer> otherPlannedDests = coordinationState.getOtherPlannedDestinations(colour);
        final boolean suppressPenalty = isSingleton;
        
        uk.ac.bris.cs.scotlandyard.model.TicketMove phase2Best = ticketMoves.stream()
            .max(java.util.Comparator.comparingDouble(m -> 
                scoreMove(m.destination(), targetSet, roleTarget, isEncircler,
                         detectiveLocations, 
                         suppressPenalty ? java.util.Collections.emptySet() : otherPlannedDests,
                         !suppressPenalty, isRevealRound && revealedPosition > 0, revealedPosition)))
            .orElse(phase1Best);
        
        callback.accept(phase2Best);
    }
    
    /**
     * Get ticket priority for capture moves (lower is better).
     * TAXI > BUS > UNDERGROUND > SECRET
     */
    private int getTicketPriority(Ticket ticket) {
        switch (ticket) {
            case TAXI: return 0;
            case BUS: return 1;
            case UNDERGROUND: return 2;
            case SECRET: return 3;
            case DOUBLE: return 4;
            default: return 5;
        }
    }
    
    /**
     * Get all detective locations from the view as a set.
     */
    private Set<Integer> getDetectiveLocations(ScotlandYardView view) {
        Set<Integer> locations = new HashSet<>();
        for (Colour colour : view.getPlayers()) {
            if (colour.isDetective()) {
                view.getPlayerLocation(colour).ifPresent(locations::add);
            }
        }
        return locations;
    }
    
    /**
     * Get all detective locations from the view as a map.
     */
    private Map<Colour, Integer> getDetectiveLocationMap(ScotlandYardView view) {
        Map<Colour, Integer> locationMap = new HashMap<>();
        for (Colour colour : view.getPlayers()) {
            if (colour.isDetective()) {
                view.getPlayerLocation(colour).ifPresent(loc -> locationMap.put(colour, loc));
            }
        }
        return locationMap;
    }
    
    /**
     * Get sorted list of detective colours (by Colour.name() lexicographic order).
     */
    private List<Colour> getSortedDetectives(ScotlandYardView view) {
        return view.getPlayers().stream()
            .filter(Colour::isDetective)
            .sorted(java.util.Comparator.comparing(Colour::name))
            .collect(Collectors.toList());
    }

    // ========== Task 4: Prediction Helper Methods ==========
    
    /**
     * Infer Mr X's transport mode from recent move history.
     * Walks backwards through tracker.moveHistorySinceLastReveal and returns the last non-SECRET ticket.
     * Returns null if no non-SECRET tickets found or history is empty.
     * 
     * @return inferred transport mode (TAXI, BUS, UNDERGROUND) or null
     */
    private Ticket getInferredTransportMode() {
        List<Ticket> history = tracker.getMoveHistorySinceLastReveal();
        
        // Walk backwards through history
        for (int i = history.size() - 1; i >= 0; i--) {
            Ticket ticket = history.get(i);
            if (ticket != Ticket.SECRET && ticket != Ticket.DOUBLE) {
                return ticket;
            }
        }
        
        return null;  // No non-SECRET tickets found
    }

    /**
     * Sample candidates for prediction scoring.
     * Returns full set if ≤50 nodes, otherwise returns a shuffled sample of 50.
     * 
     * @param candidates the full candidate set
     * @return sampled candidate set
     */
    private Set<Integer> sampleCandidates(Set<Integer> candidates) {
        if (candidates.size() <= 50) {
            return candidates;
        }
        
        // Sample 50 nodes randomly
        List<Integer> candidateList = new ArrayList<>(candidates);
        java.util.Collections.shuffle(candidateList);
        return new HashSet<>(candidateList.subList(0, 50));
    }

    /**
     * Compute predicted positions with weights based on reachability and safety.
     * Formula: weight = reachabilityCount × safetyScore
     * Filters by inferred transport mode if available.
     * 
     * @param candidates the candidate set to score
     * @param detectiveLocations current detective locations
     * @param inferredMode inferred transport mode (or null for no filtering)
     * @return map of candidate location to weight
     */
    private Map<Integer, Double> computePredictedPositions(
            Set<Integer> candidates,
            Set<Integer> detectiveLocations,
            Ticket inferredMode) {
        
        // Build reachability map: for each node, count how many candidates can reach it
        Map<Integer, Integer> reachabilityCount = new HashMap<>();
        
        for (int candidate : candidates) {
            // For each candidate, find all nodes it can reach in one move
            for (int neighbor : graphAnalyzer.getAdjacentLocations(candidate)) {
                // Filter by inferred transport mode if provided
                if (inferredMode != null) {
                    if (!canReachWithTicket(candidate, neighbor, inferredMode)) {
                        continue;
                    }
                }
                // Increment reachability count for this neighbor
                reachabilityCount.merge(neighbor, 1, Integer::sum);
            }
        }
        
        // Build weights map: weight = reachabilityCount × safetyScore (clamped to ≥ 0)
        Map<Integer, Double> weights = new HashMap<>();
        for (Map.Entry<Integer, Integer> entry : reachabilityCount.entrySet()) {
            int node = entry.getKey();
            double safetyScore = graphAnalyzer.calculateSafetyScore(node, detectiveLocations);
            // Clamp safety score to ≥ 0
            double clampedSafety = Math.max(0.0, safetyScore);
            double weight = entry.getValue() * clampedSafety;
            weights.put(node, weight);
        }
        
        return weights;
    }

    /**
     * Get top N predicted positions sorted by descending weight.
     * 
     * @param weights map of candidate location to weight
     * @param n number of top positions to return
     * @return list of top N candidate locations sorted by descending weight
     */
    private List<Integer> getTopPredictedPositions(
            Map<Integer, Double> weights,
            int n) {
        
        return weights.entrySet().stream()
            .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
            .limit(n)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    /**
     * Check if a location can reach another location with a specific ticket type.
     * 
     * @param from source location
     * @param to destination location
     * @param ticket ticket type to check
     * @return true if 'from' can reach 'to' using 'ticket'
     */
    private boolean canReachWithTicket(int from, int to, Ticket ticket) {
        if (ticket == Ticket.SECRET || ticket == Ticket.DOUBLE) {
            // SECRET and DOUBLE can use any transport
            return graphAnalyzer.getAdjacentLocations(from).contains(to);
        }
        
        // Check if the edge between 'from' and 'to' supports this ticket type
        Collection<Transport> transports = graphAnalyzer.getAllTransportsBetween(from, to);
        for (Transport transport : transports) {
            if (Ticket.fromTransport(transport) == ticket) {
                return true;
            }
        }
        
        return false;
    }

    // ========== Task 6: Role-Specific Move Generation ==========
    
    /**
     * Compute centroid of candidate set as the medoid (node with minimum sum of distances).
     * Samples up to 50 nodes for performance.
     * 
     * @param candidates the candidate set
     * @return centroid node, or -1 if candidates is empty
     */
    private int computeCentroid(Set<Integer> candidates) {
        if (candidates.isEmpty()) return -1;
        if (candidates.size() == 1) return candidates.iterator().next();

        // Sample for performance
        List<Integer> sampled = new ArrayList<>(candidates);
        if (sampled.size() > 50) {
            java.util.Collections.shuffle(sampled);
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

    /**
     * Get the target node for a specific role.
     * 
     * @param role the detective's assigned role
     * @param topPredicted top predicted Mr X positions
     * @param candidates full candidate set
     * @param detectiveLocations all detective locations
     * @param myLocation this detective's current location
     * @return target node for this role
     */
    private int getRoleTarget(
            CoordinationState.Role role,
            List<Integer> topPredicted,
            Set<Integer> candidates,
            Set<Integer> detectiveLocations,
            int myLocation) {
        
        switch (role) {
            case INTERCEPTOR:
                // Target: top predicted position (closest to detective if multiple)
                if (!topPredicted.isEmpty()) {
                    if (topPredicted.size() == 1) {
                        return topPredicted.get(0);
                    }
                    // Find closest top predicted position to this detective
                    int closest = topPredicted.get(0);
                    int minDist = graphAnalyzer.calculateShortestPath(myLocation, closest);
                    for (int i = 1; i < topPredicted.size(); i++) {
                        int dist = graphAnalyzer.calculateShortestPath(myLocation, topPredicted.get(i));
                        if (dist != -1 && (minDist == -1 || dist < minDist)) {
                            minDist = dist;
                            closest = topPredicted.get(i);
                        }
                    }
                    return closest;
                }
                return computeCentroid(candidates);  // Fallback

            case BLOCKER:
                // Target: nearest unreserved hub adjacent to candidate set
                Set<Integer> allHubs = graphAnalyzer.getTransportHubs();
                Set<Integer> adjacentHubs = new HashSet<>();
                
                // Find hubs adjacent to candidate set
                for (int candidate : candidates) {
                    for (int neighbor : graphAnalyzer.getAdjacentLocations(candidate)) {
                        if (allHubs.contains(neighbor) && !coordinationState.isHubReserved(neighbor)) {
                            adjacentHubs.add(neighbor);
                        }
                    }
                }
                
                if (!adjacentHubs.isEmpty()) {
                    // Find nearest adjacent hub to this detective
                    int nearest = -1;
                    int minDistBlocker = Integer.MAX_VALUE;
                    for (int hub : adjacentHubs) {
                        int dist = graphAnalyzer.calculateShortestPath(myLocation, hub);
                        if (dist != -1 && dist < minDistBlocker) {
                            minDistBlocker = dist;
                            nearest = hub;
                        }
                    }
                    if (nearest != -1) return nearest;
                }
                
                // Fallback: nearest unreserved hub anywhere
                int nearestHub = -1;
                int minDistFallback = Integer.MAX_VALUE;
                for (int hub : allHubs) {
                    if (!coordinationState.isHubReserved(hub)) {
                        int dist = graphAnalyzer.calculateShortestPath(myLocation, hub);
                        if (dist != -1 && dist < minDistFallback) {
                            minDistFallback = dist;
                            nearestHub = hub;
                        }
                    }
                }
                return nearestHub != -1 ? nearestHub : computeCentroid(candidates);

            case ENCIRCLER:
                // Target: nearest perimeter node (adjacent to candidate set but not in it)
                Set<Integer> perimeter = new HashSet<>();
                for (int candidate : candidates) {
                    for (int neighbor : graphAnalyzer.getAdjacentLocations(candidate)) {
                        if (!candidates.contains(neighbor)) {
                            perimeter.add(neighbor);
                        }
                    }
                }
                
                if (!perimeter.isEmpty()) {
                    int nearest = -1;
                    int minDistEncircler = Integer.MAX_VALUE;
                    for (int node : perimeter) {
                        int dist = graphAnalyzer.calculateShortestPath(myLocation, node);
                        if (dist != -1 && dist < minDistEncircler) {
                            minDistEncircler = dist;
                            nearest = node;
                        }
                    }
                    if (nearest != -1) return nearest;
                }
                return computeCentroid(candidates);  // Fallback

            case CHASER:
                // Target: centroid of candidate set
                return computeCentroid(candidates);

            case FLANKER:
                // Target: nearest node reachable from candidates, not within distance 2 of planned destinations
                Set<Integer> otherPlannedDests = coordinationState.getOtherPlannedDestinations(colour);
                
                // Build avoid zone (distance ≤ 2 from any planned destination)
                Set<Integer> avoidZone = new HashSet<>();
                for (int dest : otherPlannedDests) {
                    avoidZone.add(dest);
                    // Add distance-1 neighbors
                    for (int neighbor1 : graphAnalyzer.getAdjacentLocations(dest)) {
                        avoidZone.add(neighbor1);
                        // Add distance-2 neighbors
                        for (int neighbor2 : graphAnalyzer.getAdjacentLocations(neighbor1)) {
                            avoidZone.add(neighbor2);
                        }
                    }
                }
                
                // Find all nodes reachable from candidate set
                Set<Integer> reachableFromCandidates = new HashSet<>();
                for (int candidate : candidates) {
                    reachableFromCandidates.addAll(graphAnalyzer.getAdjacentLocations(candidate));
                }
                
                // Filter out avoid zone
                reachableFromCandidates.removeAll(avoidZone);
                
                // Find nearest node to this detective
                if (!reachableFromCandidates.isEmpty()) {
                    int nearest = -1;
                    int minDistFlanker = Integer.MAX_VALUE;
                    for (int node : reachableFromCandidates) {
                        int dist = graphAnalyzer.calculateShortestPath(myLocation, node);
                        if (dist != -1 && dist < minDistFlanker) {
                            minDistFlanker = dist;
                            nearest = node;
                        }
                    }
                    if (nearest != -1) return nearest;
                }
                
                // Fallback: if no valid flanking position, use centroid
                return computeCentroid(candidates);

            default:
                return computeCentroid(candidates);
        }
    }

    /**
     * Score a move destination using the composite formula.
     * Formula: score = W1 * candidatesCovered + W2 * roleTargetProximity + W3 * hubValue
     * Where W1=2.0, W2=1.0, W3=0.5
     * 
     * @param destination the destination node to score
     * @param targetSet the target set (zone or full candidate set)
     * @param roleTarget the role-specific target node
     * @param isEncircler true if this detective is ENCIRCLER
     * @param detectiveLocations all detective locations
     * @param plannedDests other detectives' planned destinations
     * @param applyCoordinationPenalty true to apply collision penalty
     * @param revealBonus true if this is a reveal round
     * @param revealedPosition Mr X's last revealed position (-1 if none)
     * @return composite score for this destination
     */
    private double scoreMove(
            int destination,
            Set<Integer> targetSet,
            int roleTarget,
            boolean isEncircler,
            Set<Integer> detectiveLocations,
            Set<Integer> plannedDests,
            boolean applyCoordinationPenalty,
            boolean revealBonus,
            int revealedPosition) {
        
        // Constants from design spec
        final double W1 = 2.0;  // candidatesCovered weight
        final double W2 = 1.0;  // roleTargetProximity weight
        final double W3 = 0.5;  // hubValue weight
        final int MAX_BOARD_DISTANCE = 20;
        final double REVEAL_BONUS = 10.0;
        final double COORDINATION_PENALTY = 5.0;
        
        // W1: candidatesCovered
        int covered = 0;
        if (isEncircler) {
            // Count perimeter nodes within dist 2 NOT covered by others
            for (int node : targetSet) {
                int dist = graphAnalyzer.calculateShortestPath(destination, node);
                if (dist != -1 && dist <= 2 && !plannedDests.contains(node)) {
                    covered++;
                }
            }
        } else {
            for (int node : targetSet) {
                int dist = graphAnalyzer.calculateShortestPath(destination, node);
                if (dist != -1 && dist <= 2) {
                    covered++;
                }
            }
        }
        
        // W2: roleTargetProximity = MAX_BOARD_DISTANCE - dist
        int distToTarget = graphAnalyzer.calculateShortestPath(destination, roleTarget);
        int proximity = MAX_BOARD_DISTANCE - (distToTarget == -1 ? MAX_BOARD_DISTANCE : distToTarget);
        
        // W3: hubValue
        double hubValue = graphAnalyzer.isTransportHub(destination) ? 1.0 : 0.0;
        
        // Base score
        double score = W1 * covered + W2 * proximity + W3 * hubValue;
        
        // Tiebreaker: tiny connectivity bonus
        score += graphAnalyzer.getConnectivity(destination) * 0.001;
        
        // Reveal bonus
        if (revealBonus && revealedPosition > 0) {
            int distToRevealed = graphAnalyzer.calculateShortestPath(destination, revealedPosition);
            if (distToRevealed != -1 && distToRevealed <= 2) {
                score += REVEAL_BONUS;
            }
        }
        
        // Coordination penalty
        if (applyCoordinationPenalty && plannedDests.contains(destination)) {
            score -= COORDINATION_PENALTY;
        }
        
        return score;
    }
}

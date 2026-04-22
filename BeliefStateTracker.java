package uk.ac.bris.cs.scotlandyard.ai;

import uk.ac.bris.cs.scotlandyard.model.*;
import java.util.*;

import static uk.ac.bris.cs.scotlandyard.ai.ScoringConfig.*;

/**
 * Tracks what the detectives believe about Mr. X's location.
 * This is THE most critical component for a strong Mr. X AI.
 * 
 * Mr. X wins by controlling INFORMATION, not just distance.
 * 
 * IMPROVEMENTS:
 * - Uses information entropy (Shannon entropy) for ambiguity measurement
 * - Simplified cognitive complexity model (removed speculative psychology)
 * - Integrated with ScoringConfig for consistent thresholds
 */
public class BeliefStateTracker {
    
    private final GraphAnalyzer graphAnalyzer;
    private Set<Integer> possibleLocations;
    private int lastRevealedLocation;
    private int lastRevealRound;
    private int movesSinceReveal;
    private final Map<Ticket, Integer> estimatedRemainingTickets;
    
    public BeliefStateTracker(GraphAnalyzer graphAnalyzer, int startLocation) {
        this(graphAnalyzer, startLocation, defaultMrXTickets());
    }

    public BeliefStateTracker(GraphAnalyzer graphAnalyzer, int startLocation, Map<Ticket, Integer> initialTickets) {
        this.graphAnalyzer = graphAnalyzer;
        this.possibleLocations = new HashSet<>();
        this.possibleLocations.add(startLocation);
        this.lastRevealedLocation = startLocation;
        this.lastRevealRound = 0;
        this.movesSinceReveal = 0;
        this.estimatedRemainingTickets = new HashMap<>(initialTickets);
    }
    
    /**
     * Update belief state after Mr. X makes a move.
     * 
     * @param ticket The ticket type used (what detectives see)
     * @param actualDestination Mr. X's actual destination (hidden from detectives)
     * @param isRevealRound Whether this round reveals Mr. X's position
     */
    public void updateAfterMove(Ticket ticket, int actualDestination, boolean isRevealRound) {
        decrementEstimatedTicket(ticket);
        if (isRevealRound) {
            possibleLocations.clear();
            possibleLocations.add(actualDestination);
            lastRevealedLocation = actualDestination;
            movesSinceReveal = 0;
        } else {
            movesSinceReveal++;
            expandBeliefWithTicket(ticket);
            possibleLocations = applyRevealConstraint(possibleLocations);
        }
    }
    
    /**
     * Update belief state after Mr. X makes a double move.
     * Detectives only know a double was used (since they see one ticket but position moved twice),
     * but they don't know the intermediate or final destination until reveal.
     * 
     * @param firstTicket The first ticket used
     * @param secondTicket The second ticket used  
     * @param finalDestination Mr. X's final destination (hidden until reveal)
     * @param isRevealRound Whether this round reveals Mr. X's position
     */
    public void updateAfterDoubleMove(Ticket firstTicket, Ticket secondTicket, 
                                      int finalDestination, boolean isRevealRound) {
        if (isRevealRound) {
            possibleLocations.clear();
            possibleLocations.add(finalDestination);
            lastRevealedLocation = finalDestination;
            movesSinceReveal = 0;
        } else {
            movesSinceReveal += 2;
            decrementEstimatedTicket(Ticket.DOUBLE);
            decrementEstimatedTicket(firstTicket);
            decrementEstimatedTicket(secondTicket);
            expandBeliefWithTicket(firstTicket);
            expandBeliefWithTicket(secondTicket);
            possibleLocations = applyRevealConstraint(possibleLocations);
        }
    }
    
    private void expandBeliefWithTicket(Ticket ticket) {
        Set<Integer> newPossible = new HashSet<>();
        for (int currentBelief : possibleLocations) {
            Set<Integer> reachable = getReachableWithTicket(currentBelief, ticket);
            newPossible.addAll(reachable);
        }
        if (!newPossible.isEmpty()) {
            possibleLocations = newPossible;
        }
    }
    
    private Set<Integer> applyRevealConstraint(Set<Integer> beliefs) {
        if (lastRevealedLocation <= 0) return beliefs;
        Set<Integer> reachableFromReveal =
            getReachableInNMovesTicketAware(lastRevealedLocation, movesSinceReveal);
        if (reachableFromReveal.isEmpty()) {
            reachableFromReveal = getReachableInNMovesWithRelaxedTickets(
                lastRevealedLocation, movesSinceReveal);
        }
        Set<Integer> constrained = new HashSet<>(beliefs);
        constrained.retainAll(reachableFromReveal);
        if (constrained.isEmpty()) {
            if (!beliefs.isEmpty()) {
                return new HashSet<>(beliefs);
            }
            return new HashSet<>(reachableFromReveal);
        }
        return constrained;
    }

    private void decrementEstimatedTicket(Ticket ticket) {
        estimatedRemainingTickets.computeIfPresent(ticket, (t, count) -> Math.max(0, count - 1));
    }
    
    /**
     * Get all locations reachable from a position using a specific ticket type.
     */
    private Set<Integer> getReachableWithTicket(int from, Ticket ticket) {
        Set<Integer> reachable = new HashSet<>();
        Set<Integer> adjacent = graphAnalyzer.getAdjacentLocations(from);
        
        for (int dest : adjacent) {
            Transport transport = graphAnalyzer.findTransportBetween(from, dest);
            if (transport != null) {
                Ticket requiredTicket = Ticket.fromTransport(transport);
                
                // SECRET tickets can use any transport
                if (ticket == Ticket.SECRET || requiredTicket == ticket) {
                    reachable.add(dest);
                }
            }
        }
        
        return reachable;
    }

    private Set<Integer> getReachableInNMoves(int start, int n) {
        Set<Integer> reachable = new HashSet<>();
        Queue<int[]> queue = new LinkedList<>();
        queue.offer(new int[]{start, 0});
        reachable.add(start);

        while (!queue.isEmpty()) {
            int[] current = queue.poll();
            int location = current[0];
            int depth = current[1];
            if (depth >= n) continue;

            for (int adjacent : graphAnalyzer.getAdjacentLocations(location)) {
                if (reachable.add(adjacent)) {
                    queue.offer(new int[]{adjacent, depth + 1});
                }
            }
        }

        return reachable;
    }

    private Set<Integer> getReachableInNMovesTicketAware(int start, int n) {
        Set<Integer> reachable = new HashSet<>();
        Queue<TicketState> queue = new LinkedList<>();
        queue.offer(new TicketState(start, 0, new HashMap<>(estimatedRemainingTickets)));
        reachable.add(start);

        while (!queue.isEmpty()) {
            TicketState current = queue.poll();
            if (current.depth >= n) continue;

            for (int adjacent : graphAnalyzer.getAdjacentLocations(current.location)) {
                Transport transport = graphAnalyzer.findTransportBetween(current.location, adjacent);
                if (transport == null) continue;

                Ticket required = Ticket.fromTransport(transport);
                int specific = current.remainingTickets.getOrDefault(required, 0);
                int secret = current.remainingTickets.getOrDefault(Ticket.SECRET, 0);
                if (specific <= 0 && secret <= 0) continue;

                Map<Ticket, Integer> nextTickets = new HashMap<>(current.remainingTickets);
                if (specific > 0) {
                    nextTickets.put(required, specific - 1);
                } else {
                    nextTickets.put(Ticket.SECRET, secret - 1);
                }

                reachable.add(adjacent);
                queue.offer(new TicketState(adjacent, current.depth + 1, nextTickets));
            }
        }

        return reachable;
    }

    private Set<Integer> getReachableInNMovesWithRelaxedTickets(int start, int n) {
        int relaxedTaxi = estimatedRemainingTickets.getOrDefault(Ticket.TAXI, 4) + 2;
        int relaxedBus = estimatedRemainingTickets.getOrDefault(Ticket.BUS, 3) + 2;
        int relaxedUnderground = estimatedRemainingTickets.getOrDefault(Ticket.UNDERGROUND, 3) + 1;
        int relaxedSecret = estimatedRemainingTickets.getOrDefault(Ticket.SECRET, 5) + 3;
        
        Set<Integer> reachable = new HashSet<>();
        Queue<TicketState> queue = new LinkedList<>();
        Map<Ticket, Integer> relaxedTickets = new HashMap<>();
        relaxedTickets.put(Ticket.TAXI, relaxedTaxi);
        relaxedTickets.put(Ticket.BUS, relaxedBus);
        relaxedTickets.put(Ticket.UNDERGROUND, relaxedUnderground);
        relaxedTickets.put(Ticket.SECRET, relaxedSecret);
        queue.offer(new TicketState(start, 0, relaxedTickets));
        reachable.add(start);

        while (!queue.isEmpty()) {
            TicketState current = queue.poll();
            if (current.depth >= n) continue;

            for (int adjacent : graphAnalyzer.getAdjacentLocations(current.location)) {
                Transport transport = graphAnalyzer.findTransportBetween(current.location, adjacent);
                if (transport == null) continue;

                Ticket required = Ticket.fromTransport(transport);
                int specific = current.remainingTickets.getOrDefault(required, 0);
                int secret = current.remainingTickets.getOrDefault(Ticket.SECRET, 0);
                if (specific <= 0 && secret <= 0) continue;

                Map<Ticket, Integer> nextTickets = new HashMap<>(current.remainingTickets);
                if (specific > 0) {
                    nextTickets.put(required, specific - 1);
                } else {
                    nextTickets.put(Ticket.SECRET, secret - 1);
                }

                if (reachable.add(adjacent)) {
                    queue.offer(new TicketState(adjacent, current.depth + 1, nextTickets));
                }
            }
        }

        return reachable;
    }

    private static Map<Ticket, Integer> defaultMrXTickets() {
        Map<Ticket, Integer> defaults = new HashMap<>();
        defaults.put(Ticket.TAXI, 4);
        defaults.put(Ticket.BUS, 3);
        defaults.put(Ticket.UNDERGROUND, 3);
        defaults.put(Ticket.DOUBLE, 2);
        defaults.put(Ticket.SECRET, 5);
        return defaults;
    }

    private static final class TicketState {
        final int location;
        final int depth;
        final Map<Ticket, Integer> remainingTickets;

        TicketState(int location, int depth, Map<Ticket, Integer> remainingTickets) {
            this.location = location;
            this.depth = depth;
            this.remainingTickets = remainingTickets;
        }
    }
    
    /**
     * Returns the ambiguity score - how many locations detectives think Mr. X could be at.
     * Higher = better for Mr. X.
     */
    public int getAmbiguityCount() {
        return possibleLocations.size();
    }
    
    /**
     * Calculate information entropy (Shannon entropy) of belief state.
     * This is a principled measure of uncertainty from information theory.
     * 
     * Entropy = -Σ(p * log₂(p)) where p = 1/candidateCount for uniform distribution
     * 
     * @return Bits of uncertainty (0 = certain, higher = more uncertain)
     */
    public double getInformationEntropy() {
        return calculateInformationEntropy(possibleLocations.size());
    }
    
    /**
     * Returns the set of locations detectives believe Mr. X could be at.
     */
    public Set<Integer> getPossibleLocations() {
        return new HashSet<>(possibleLocations);
    }
    
    /**
     * Get the last location Mr. X was revealed at.
     */
    public int getLastRevealedLocation() {
        return lastRevealedLocation;
    }
    
    /**
     * Get number of moves since last reveal.
     */
    public int getMovesSinceReveal() {
        return movesSinceReveal;
    }
    
    /**
     * Calculate how much ambiguity a move would create.
     * This is the KEY metric for Mr. X's decision making.
     */
    public int calculateAmbiguityAfterMove(int currentLocation, Ticket ticket, int destination) {
        // Simulate what detectives would believe after this move
        Set<Integer> simulatedBelief = new HashSet<>();
        
        for (int belief : possibleLocations) {
            Set<Integer> reachable = getReachableWithTicket(belief, ticket);
            simulatedBelief.addAll(reachable);
        }
        
        return simulatedBelief.size();
    }
    
    /**
     * Evaluate how "confused" the detectives would be after a move.
     * Returns a score where higher = more confusion = better for Mr. X.
     */
    public double evaluateAmbiguityScore(int currentLocation, Ticket ticket, int destination) {
        int ambiguityAfter = calculateAmbiguityAfterMove(currentLocation, ticket, destination);
        
        // Normalize based on graph size (Scotland Yard has ~200 nodes)
        double normalizedAmbiguity = Math.min(ambiguityAfter / 20.0, 10.0);
        
        // Extra bonus for SECRET tickets - they create maximum ambiguity
        if (ticket == Ticket.SECRET) {
            normalizedAmbiguity *= 1.5;
        }
        
        return normalizedAmbiguity * 10.0; // Scale to match other scoring components
    }
    
    /**
     * Check if detectives' belief state includes the actual location.
     * If not, Mr. X has successfully "escaped" their mental model.
     */
    public boolean detectivesKnowActualLocation(int actualLocation) {
        return possibleLocations.contains(actualLocation);
    }
    
    /**
     * Get the "center of mass" of detective beliefs.
     * Useful for planning moves away from where they think you are.
     */
    public int getBeliefCentroid() {
        if (possibleLocations.isEmpty()) return lastRevealedLocation;
        
        // Simple average of all believed locations
        int sum = 0;
        for (int loc : possibleLocations) {
            sum += loc;
        }
        return sum / possibleLocations.size();
    }
    
    /**
     * Evaluate cognitive complexity using simplified, principled approach.
     * 
     * SIMPLIFIED from original speculative psychology model to use:
     * 1. Information entropy (bits of uncertainty)
     * 2. Candidate spread (geographic distribution)
     * 3. Transport diversity (filtering difficulty)
     * 
     * This is more defensible than arbitrary thresholds about human cognition.
     */
    public double evaluateCognitiveComplexity(int currentLocation, Ticket ticket, 
                                               int destination, List<Ticket> last3Tickets,
                                               boolean isRevealRound) {
        if (!isRevealRound) {
            // Not a reveal round, use basic ambiguity scoring
            return evaluateAmbiguityScore(currentLocation, ticket, destination);
        }
        
        // This IS a reveal round - maximize cognitive difficulty
        
        // Factor 1: Information entropy (principled measure)
        int candidateSetSize = calculateAmbiguityAfterMove(currentLocation, ticket, destination);
        double entropy = calculateInformationEntropy(candidateSetSize);
        
        // Convert entropy to score (scale to match other components)
        // 0 bits = 0 score, 5 bits (32 candidates) = 100 score
        double candidateScore = Math.min(entropy * 20.0, 100.0);
        
        // Legacy thresholds for backward compatibility (can be removed later)
        if (candidateSetSize > CANDIDATE_SET_OVERLOAD) {
            candidateScore = Math.max(candidateScore, COGNITIVE_OVERLOAD_SCORE);
        } else if (candidateSetSize > CANDIDATE_SET_VERY_DIFFICULT) {
            candidateScore = Math.max(candidateScore, COGNITIVE_VERY_DIFFICULT_SCORE);
        } else if (candidateSetSize > CANDIDATE_SET_MODERATE) {
            candidateScore = Math.max(candidateScore, COGNITIVE_MODERATE_SCORE);
        } else if (candidateSetSize > CANDIDATE_SET_EASY) {
            candidateScore = Math.max(candidateScore, COGNITIVE_EASY_SCORE);
        }
        
        // Factor 2: Candidate spread quality (prevents clustered false positives)
        double spreadMultiplier = 1.0;
        if (candidateSetSize > CANDIDATE_SET_EASY) {
            spreadMultiplier = calculateCandidateSpreadQuality();
        }
        
        // Factor 3: Transport diversity in recent moves
        double diversityMultiplier = calculateTransportDiversityMultiplier(ticket, last3Tickets);
        
        // Factor 4: Distance from previous reveal
        double distanceBonus = calculateRevealDistanceBonus(destination);
        
        // Combine all factors
        double totalScore = (candidateScore * spreadMultiplier * diversityMultiplier) + distanceBonus;
        
        return totalScore;
    }
    
    /**
     * Track recent ticket usage for cognitive complexity calculation.
     */
    private List<Ticket> recentTickets = new ArrayList<>();
    
    public void recordTicketUsage(Ticket ticket) {
        recentTickets.add(ticket);
        // Keep only last 3 tickets
        if (recentTickets.size() > 3) {
            recentTickets.remove(0);
        }
    }
    
    public List<Ticket> getLast3Tickets() {
        return new ArrayList<>(recentTickets);
    }
    
    // ==================== HELPER METHODS ====================
    
    /**
     * Calculate quality of candidate spread (prevents clustered false positives).
     * Returns multiplier in range [0.5, 1.0].
     */
    private double calculateCandidateSpreadQuality() {
        Set<Integer> candidates = new HashSet<>(possibleLocations);
        if (candidates.size() < 3) return 1.0;
        
        // Calculate average distance between candidates (sample up to 5)
        double totalDist = 0;
        int pairs = 0;
        List<Integer> candList = new ArrayList<>(candidates);
        int sampleSize = Math.min(5, candList.size());
        
        for (int i = 0; i < sampleSize; i++) {
            for (int j = i + 1; j < sampleSize; j++) {
                int dist = graphAnalyzer.calculateShortestPath(candList.get(i), candList.get(j));
                if (dist > 0) {
                    totalDist += dist;
                    pairs++;
                }
            }
        }
        
        double avgSpread = pairs > 0 ? totalDist / pairs : 0;
        
        // Calculate spread multiplier
        double spreadMultiplier;
        if (avgSpread < 3) {
            spreadMultiplier = 0.5; // Clustered = easy to eliminate
        } else if (avgSpread < 5) {
            spreadMultiplier = 0.8; // Moderate spread
        } else {
            spreadMultiplier = 1.0; // Well spread
        }
        
        // Check transport diversity across candidates
        Set<Transport> requiredTransports = new HashSet<>();
        for (int candidate : candList) {
            Set<Integer> neighbors = graphAnalyzer.getAdjacentLocations(candidate);
            if (neighbors != null) {
                for (int neighbor : neighbors) {
                    Transport t = graphAnalyzer.findTransportBetween(candidate, neighbor);
                    if (t != null) {
                        requiredTransports.add(t);
                    }
                }
            }
        }
        
        // Adjust for transport diversity
        if (requiredTransports.size() == 1) {
            spreadMultiplier *= 0.6; // Single transport = easy to block
        } else if (requiredTransports.size() == 2) {
            spreadMultiplier *= 0.9; // Two transports = moderate difficulty
        }
        
        return spreadMultiplier;
    }
    
    /**
     * Calculate transport diversity multiplier based on recent ticket usage.
     * Returns multiplier in range [1.0, 1.8].
     */
    private double calculateTransportDiversityMultiplier(Ticket currentTicket, List<Ticket> last3Tickets) {
        Set<Ticket> transportTypesUsed = new HashSet<>();
        
        if (last3Tickets != null) {
            for (Ticket t : last3Tickets) {
                if (t != Ticket.DOUBLE && t != Ticket.SECRET) {
                    transportTypesUsed.add(t);
                }
            }
        }
        
        if (currentTicket != Ticket.DOUBLE && currentTicket != Ticket.SECRET) {
            transportTypesUsed.add(currentTicket);
        }
        
        // More transport types = harder to track
        if (transportTypesUsed.size() >= 3) {
            return 1.8; // Used all 3 transport types - maximum confusion
        } else if (transportTypesUsed.size() == 2) {
            return 1.4; // Used 2 types - good confusion
        } else {
            return 1.0; // Single transport type is easier to track
        }
    }
    
    /**
     * Calculate bonus for distance from previous reveal.
     * Returns bonus in range [0, 60].
     */
    private double calculateRevealDistanceBonus(int destination) {
        int distanceFromLastReveal = graphAnalyzer.calculateShortestPath(
            lastRevealedLocation, destination
        );
        
        if (distanceFromLastReveal >= 8) {
            return 60.0; // Huge jump - detectives lost track
        } else if (distanceFromLastReveal >= 6) {
            return 40.0; // Large jump - hard to predict
        } else if (distanceFromLastReveal >= 4) {
            return 20.0; // Moderate jump
        } else {
            return 0.0; // Stayed close, easier to track
        }
    }
}

package uk.ac.bris.cs.scotlandyard.ai;

import uk.ac.bris.cs.scotlandyard.model.*;
import java.util.*;

/**
 * Plans optimal positions for surface (reveal) turns.
 * 
 * Surface turns are Mr. X's biggest constraint. This planner works BACKWARDS
 * from the next reveal to ensure Mr. X surfaces in a strong position.
 */
public class SurfaceTurnPlanner {
    
    private final GraphAnalyzer graphAnalyzer;
    private final List<Boolean> rounds;
    
    // Track previous reveal positions to maximize inter-reveal distance
    private final List<Integer> revealHistory = new ArrayList<>();
    
    public SurfaceTurnPlanner(GraphAnalyzer graphAnalyzer, List<Boolean> rounds) {
        this.graphAnalyzer = graphAnalyzer;
        this.rounds = rounds;
    }
    
    /**
     * Record a reveal position for inter-reveal distance tracking.
     * Call this after each reveal round.
     */
    public void recordReveal(int location) {
        revealHistory.add(location);
    }
    
    /**
     * Get the most recent reveal position, or -1 if no reveals yet.
     */
    public int getLastRevealPosition() {
        return revealHistory.isEmpty() ? -1 : revealHistory.get(revealHistory.size() - 1);
    }
    
    /**
     * Get the round number of the next reveal.
     */
    public int getNextRevealRound(int currentRound) {
        for (int r = currentRound + 1; r <= rounds.size(); r++) {
            if (rounds.get(r - 1)) {
                return r;
            }
        }
        return -1; // No more reveals
    }
    
    /**
     * Get rounds until next reveal.
     */
    public int getRoundsUntilReveal(int currentRound) {
        int nextReveal = getNextRevealRound(currentRound);
        return nextReveal == -1 ? Integer.MAX_VALUE : nextReveal - currentRound;
    }
    
    /**
     * Evaluate how good a location is for surfacing (being revealed).
     * 
     * Good surface positions have:
     * 1. High connectivity (many exits)
     * 2. Multiple transport types available
     * 3. Far from detective cluster (average distance to ALL detectives)
     * 4. Not easily surrounded
     * 5. FAR FROM PREVIOUS REVEAL (prevents triangulation)
     * 6. Immediate escape options (nodes reachable before detectives arrive)
     * 7. Not convergence point for multiple detectives
     */
    public double evaluateSurfacePosition(int location, Set<Integer> detectiveLocations) {
        double score = 0;
        
        // 1. Calculate guaranteed exits (exits NOT contested by detectives)
        int connectivity = graphAnalyzer.getConnectivity(location);
        int contestedExits = graphAnalyzer.getContestedExits(location, detectiveLocations);
        int guaranteedExits = Math.max(0, connectivity - contestedExits);
        
        // Hard veto for surface traps - guaranteed exits are essential for survival
        if (guaranteedExits <= 1) {
            return Double.NEGATIVE_INFINITY;
        }
        
        // Weight guaranteed exits heavily
        score += guaranteedExits * 25.0;
        
        // Also add small bonus for raw connectivity (transport diversity matters too)
        score += connectivity * 5.0;
        
        // 2. Transport diversity - can escape via multiple routes
        Set<Transport> availableTransports = getAvailableTransports(location);
        score += availableTransports.size() * 12.0;
        
        // 3. Distance from detectives - use AVERAGE distance to ALL detectives
        int totalDist = 0;
        int minDetectiveDist = Integer.MAX_VALUE;
        for (int detLoc : detectiveLocations) {
            int dist = graphAnalyzer.calculateShortestPath(location, detLoc);
            if (dist >= 0) {
                totalDist += dist;
                minDetectiveDist = Math.min(minDetectiveDist, dist);
            }
        }
        int avgDetectiveDist = detectiveLocations.isEmpty() ? 10 : totalDist / detectiveLocations.size();
        if (minDetectiveDist != Integer.MAX_VALUE) {
            // Weighted: prioritize both minimum distance AND average distance
            score += minDetectiveDist * 8.0 + avgDetectiveDist * 5.0;
        }
        
        // 4. Not easily surrounded - check if exits are contestable
        // Use contestedExits computed above (line 75-76)
        double contestRatio = connectivity == 0 ? 1.0 : (double) contestedExits / connectivity;
        score -= contestRatio * 40.0;
        
        // 5. Future mobility - exits from this position also have good connectivity
        double futureMobility = graphAnalyzer.getFutureMobilityScore(location, detectiveLocations);
        score += futureMobility * 8.0;
        
        // 6. Escape volume - how many nodes reachable in 2 moves
        int escapeVolume = graphAnalyzer.getEscapeVolume(location, detectiveLocations, 2);
        score += escapeVolume * 3.0;
        
        // 7. INTER-REVEAL DISTANCE - maximize distance from previous reveal
        int lastReveal = getLastRevealPosition();
        if (lastReveal != -1) {
            int interRevealDist = graphAnalyzer.calculateShortestPath(lastReveal, location);
            if (interRevealDist >= 0) {
                score += interRevealDist * 35.0;  // Increased from 20.0 - large jumps critical
                if (isDifferentTransportZone(lastReveal, location)) {
                    score += 50.0;
                }
            }
        }
        
        // 8. IMMEDIATE ESCAPE OPTIONS - weight high (within 1 move before detectives arrive)
        int immediateEscape = countImmediateEscapeNodes(location, detectiveLocations);
        score += immediateEscape * 10.0;  // High weight for immediate options
        
        // 9. DETECTIVE CONVERGENCE - penalize if multiple detectives converging toward this node
        int convergingDetectives = countConvergingDetectives(location, detectiveLocations);
        if (convergingDetectives >= 2) {
            score -= (convergingDetectives - 1) * 30.0;  // -30 per extra converging detective
        }
        
        // 10. ANTI-SURROUND PENALTY - if most exits are contested, very risky
        if (contestRatio >= 0.8 && connectivity <= 3) {
            score -= 60.0;  // Heavy penalty for nearly surrounded
        }
        
        return score;
    }
    
    /**
     * Count nodes reachable in 1 move BEFORE any detective can reach them.
     * Higher = better immediate escape options.
     */
    private int countImmediateEscapeNodes(int location, Set<Integer> detectiveLocations) {
        Set<Integer> mrXExits = graphAnalyzer.getAdjacentLocations(location);
        int safeExits = 0;
        
        for (int exit : mrXExits) {
            boolean safe = true;
            for (int detLoc : detectiveLocations) {
                int detDist = graphAnalyzer.calculateShortestPath(detLoc, exit);
                if (detDist >= 0 && detDist <= 1) {  // Detective can reach in 1 move
                    safe = false;
                    break;
                }
            }
            if (safe) safeExits++;
        }
        
        return safeExits;
    }
    
    /**
     * Count how many detectives are moving toward this location.
     * Detective is "converging" if the next step brings them closer.
     */
    private int countConvergingDetectives(int location, Set<Integer> detectiveLocations) {
        int converging = 0;
        int currentDist = Integer.MAX_VALUE;
        
        for (int detLoc : detectiveLocations) {
            int dist = graphAnalyzer.calculateShortestPath(detLoc, location);
            if (dist >= 0 && dist < currentDist) {
                currentDist = dist;
                converging++;
            }
        }
        
        return converging;
    }
    
    /**
     * Check if two locations are in different transport zones.
     * Different zones = harder for detectives to predict movement patterns.
     */
    private boolean isDifferentTransportZone(int loc1, int loc2) {
        Set<Transport> zone1 = getAvailableTransports(loc1);
        Set<Transport> zone2 = getAvailableTransports(loc2);
        
        // If the dominant transport types differ, they're in different zones
        Transport dominant1 = getDominantTransport(loc1);
        Transport dominant2 = getDominantTransport(loc2);
        
        return dominant1 != dominant2;
    }
    
    /**
     * Get the dominant transport type at a location.
     */
    private Transport getDominantTransport(int location) {
        Map<Transport, Integer> transportCounts = new HashMap<>();
        Set<Integer> adjacent = graphAnalyzer.getAdjacentLocations(location);
        
        for (int dest : adjacent) {
            Transport t = graphAnalyzer.findTransportBetween(location, dest);
            if (t != null && t != Transport.FERRY) { // Ignore ferry for zone detection
                transportCounts.put(t, transportCounts.getOrDefault(t, 0) + 1);
            }
        }
        
        // Return the transport with most connections
        return transportCounts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(Transport.TAXI);
    }
    
    /**
     * Evaluate surface position with detective ticket awareness.
     * Bonus if this location's dominant transport is one detectives are low on.
     * REFINEMENT: Penalty if surfacing at underground hub when detectives have tickets.
     */
    public double evaluateSurfacePositionWithTickets(int location, Set<Integer> detectiveLocations,
                                                      Map<Ticket, Integer> detectiveTicketEstimate) {
        double base = evaluateSurfacePosition(location, detectiveLocations);
        
        // Get available transports at this location
        Set<Transport> availableTransports = getAvailableTransports(location);
        
        // Bonus if this location's dominant transport is one detectives are low on
        Transport dominant = getDominantTransport(location);
        if (dominant != null) {
            Ticket required = Ticket.fromTransport(dominant);
            int remaining = detectiveTicketEstimate.getOrDefault(required, 0);
            if (remaining <= 2) {
                base += 30.0;  // Detectives can't easily follow
            }
        }
        
        // REFINEMENT: Penalty for underground hubs when detectives have underground tickets
        if (availableTransports.contains(Transport.UNDERGROUND)) {
            int undergroundTickets = detectiveTicketEstimate.getOrDefault(Ticket.UNDERGROUND, 0);
            if (undergroundTickets > 4) {
                base -= 25.0;  // Detectives can swarm via underground
            }
        }
        
        return base;
    }
    
    /**
     * Get all transport types available from a location.
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
     * Plan the optimal path to a good surface position.
     * 
     * @param currentLocation Where Mr. X is now
     * @param roundsUntilSurface How many moves until the reveal
     * @param detectiveLocations Current detective positions
     * @param tickets Available tickets
     * @return Score bonus for moves that lead toward good surface positions
     */
    public double calculateSurfacePreparationBonus(int currentLocation, int destination, 
                                                     int roundsUntilSurface, 
                                                     Set<Integer> detectiveLocations,
                                                     Map<Ticket, Integer> tickets) {
        
        // Only apply this bonus when surface is 1-3 rounds away
        if (roundsUntilSurface < 1 || roundsUntilSurface > 3) {
            return 0;
        }
        
        Set<Integer> projectedDetLocs = projectDetectivePositions(
            detectiveLocations, destination, Math.max(0, roundsUntilSurface - 1)
        );

        // If this IS the surface turn, evaluate the destination directly
        if (roundsUntilSurface == 1) {
            return evaluateSurfacePosition(destination, projectedDetLocs) * 0.3;
        }
        
        // Otherwise, check if this move leads toward good surface positions
        Set<Integer> reachableInTime = getReachableLocations(destination, roundsUntilSurface - 1, tickets);
        
        // Find the best surface position reachable from this destination
        double bestSurfaceScore = 0;
        for (int surfaceLoc : reachableInTime) {
            Set<Integer> surfaceDetLocs = projectDetectivePositions(
                detectiveLocations, surfaceLoc, Math.max(0, roundsUntilSurface - 1)
            );
            double surfaceScore = evaluateSurfacePosition(surfaceLoc, surfaceDetLocs);
            bestSurfaceScore = Math.max(bestSurfaceScore, surfaceScore);
        }
        
        // Give a bonus proportional to the best surface position we can reach
        return bestSurfaceScore * 0.15; // Scaled down since it's future planning
    }

    /**
     * Project detective positions forward by simulating simple greedy movement.
     * All detectives move toward the target position.
     */
    private Set<Integer> projectDetectivePositions(Set<Integer> detLocs, int target, int steps) {
        if (steps <= 0 || detLocs.isEmpty()) return new HashSet<>(detLocs);

        Set<Integer> projected = new HashSet<>(detLocs);
        
        for (int step = 0; step < steps; step++) {
            Set<Integer> nextPositions = new HashSet<>();
            
            for (int detLoc : projected) {
                // Find the adjacent location closest to target
                int bestNext = detLoc;
                int bestDist = graphAnalyzer.calculateShortestPath(detLoc, target);
                if (bestDist < 0) bestDist = Integer.MAX_VALUE;
                
                for (int adj : graphAnalyzer.getAdjacentLocations(detLoc)) {
                    int dist = graphAnalyzer.calculateShortestPath(adj, target);
                    if (dist >= 0 && dist < bestDist) {
                        bestDist = dist;
                        bestNext = adj;
                    }
                }
                
                nextPositions.add(bestNext);
            }
            
            projected = nextPositions;
        }

        return projected;
    }
    
    /**
     * Get all locations reachable within N moves with available tickets.
     */
    private Set<Integer> getReachableLocations(int start, int moves, Map<Ticket, Integer> tickets) {
        if (moves <= 0) return Collections.singleton(start);
        
        Set<Integer> reachable = new HashSet<>();
        Queue<TicketState> queue = new LinkedList<>();
        Set<String> seenStates = new HashSet<>();
        Map<Ticket, Integer> remaining = new HashMap<>();
        for (Ticket ticket : Ticket.values()) {
            remaining.put(ticket, tickets.getOrDefault(ticket, 0));
        }
        queue.offer(new TicketState(start, 0, remaining));
        reachable.add(start);
        
        while (!queue.isEmpty()) {
            TicketState current = queue.poll();
            int loc = current.location;
            int depth = current.depth;
            String stateKey = buildStateKey(current);
            if (!seenStates.add(stateKey)) {
                continue;
            }
            
            if (depth >= moves) continue;
            
            Set<Integer> adjacent = graphAnalyzer.getAdjacentLocations(loc);
            for (int next : adjacent) {
                Transport transport = graphAnalyzer.findTransportBetween(loc, next);
                if (transport == null) continue;

                Ticket required = Ticket.fromTransport(transport);
                int specificCount = current.remainingTickets.getOrDefault(required, 0);
                int secretCount = current.remainingTickets.getOrDefault(Ticket.SECRET, 0);
                if (specificCount <= 0 && secretCount <= 0) continue;

                Map<Ticket, Integer> nextTickets = new HashMap<>(current.remainingTickets);
                if (specificCount > 0) {
                    nextTickets.put(required, specificCount - 1);
                } else {
                    nextTickets.put(Ticket.SECRET, secretCount - 1);
                }

                reachable.add(next);
                queue.offer(new TicketState(next, depth + 1, nextTickets));
            }
        }
        
        return reachable;
    }

    private String buildStateKey(TicketState state) {
        return state.location + ":" + state.depth + ":"
            + state.remainingTickets.getOrDefault(Ticket.TAXI, 0) + ":"
            + state.remainingTickets.getOrDefault(Ticket.BUS, 0) + ":"
            + state.remainingTickets.getOrDefault(Ticket.UNDERGROUND, 0) + ":"
            + state.remainingTickets.getOrDefault(Ticket.SECRET, 0);
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
     * Check if a location is a "trap" for surface turns.
     * Trap = low guaranteed exits = easily surrounded.
     */
    public boolean isSurfaceTrap(int location, Set<Integer> detectiveLocations) {
        int connectivity = graphAnalyzer.getConnectivity(location);
        int contestedExits = graphAnalyzer.getContestedExits(location, detectiveLocations);
        int guaranteedExits = Math.max(0, connectivity - contestedExits);
        
        return guaranteedExits <= 2;
    }
}

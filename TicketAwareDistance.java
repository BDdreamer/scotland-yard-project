package uk.ac.bris.cs.scotlandyard.ai;

import uk.ac.bris.cs.scotlandyard.model.*;
import java.util.*;

/**
 * Calculates effective distance considering detective ticket constraints.
 * 
 * SCENARIO 3: A detective with only underground tickets is effectively frozen
 * in most of the map. Raw graph distance doesn't reflect this.
 */
public class TicketAwareDistance {
    
    private final GraphAnalyzer graphAnalyzer;
    
    public TicketAwareDistance(GraphAnalyzer graphAnalyzer) {
        this.graphAnalyzer = graphAnalyzer;
    }
    
    /**
     * Calculate effective distance from a detective to Mr. X considering tickets.
     * 
     * Returns a weighted distance where:
     * - Normal distance if detective has all ticket types
     * - Increased distance if detective is missing key tickets
     * - Infinity if detective can't reach Mr. X with available tickets
     */
    public double calculateEffectiveDistance(int detectiveLocation, int mrXLocation, 
                                             Map<Ticket, Integer> detectiveTickets) {
        
        // If detective has no tickets at all, they're effectively infinite distance
        int totalTickets = 0;
        for (int count : detectiveTickets.values()) {
            totalTickets += count;
        }
        if (totalTickets == 0) {
            return Double.POSITIVE_INFINITY;
        }
        
        // Calculate raw graph distance
        int rawDistance = graphAnalyzer.calculateShortestPath(detectiveLocation, mrXLocation);
        if (rawDistance == -1) {
            return Double.POSITIVE_INFINITY;
        }
        
        // Calculate ticket-aware distance
        int ticketAwareDistance = graphAnalyzer.calculateShortestPath(detectiveLocation, mrXLocation, detectiveTickets);
        
        if (ticketAwareDistance == -1) {
            // Detective can't reach Mr. X with available tickets
            // But they might get closer - calculate how close they can get
            int closestReachable = findClosestReachableToTarget(detectiveLocation, mrXLocation, detectiveTickets);
            if (closestReachable == -1) {
                return Double.POSITIVE_INFINITY;
            }
            // Effective distance = distance to closest reachable + distance from there to target
            int remainingDist = graphAnalyzer.calculateShortestPath(closestReachable, mrXLocation);
            return closestReachable + (remainingDist >= 0 ? remainingDist : 100);
        }
        
        // Calculate mobility penalty based on ticket diversity
        double mobilityFactor = calculateMobilityFactor(detectiveTickets);
        
        // Effective distance = ticket-aware distance × mobility factor
        // Low mobility (only 1 ticket type) → higher effective distance
        return ticketAwareDistance * mobilityFactor;
    }
    
    /**
     * Find the closest node to target that detective can reach with available tickets.
     */
    private int findClosestReachableToTarget(int detectiveLocation, int target, 
                                             Map<Ticket, Integer> tickets) {
        Set<Integer> reachable = getReachableNodes(detectiveLocation, tickets, 10);
        
        int closestNode = -1;
        int minDist = Integer.MAX_VALUE;
        
        for (int node : reachable) {
            int dist = graphAnalyzer.calculateShortestPath(node, target);
            if (dist >= 0 && dist < minDist) {
                minDist = dist;
                closestNode = node;
            }
        }
        
        return closestNode;
    }
    
    /**
     * Get all nodes reachable with available tickets within max moves.
     */
    private Set<Integer> getReachableNodes(int start, Map<Ticket, Integer> tickets, int maxMoves) {
        Set<Integer> reachable = new HashSet<>();
        Queue<State> queue = new LinkedList<>();
        queue.offer(new State(start, new HashMap<>(tickets), 0));
        reachable.add(start);
        
        while (!queue.isEmpty()) {
            State current = queue.poll();
            if (current.moves >= maxMoves) continue;
            
            Set<Integer> adjacent = graphAnalyzer.getAdjacentLocations(current.location);
            for (int next : adjacent) {
                if (reachable.contains(next)) continue;
                
                Transport transport = graphAnalyzer.findTransportBetween(current.location, next);
                if (transport == null) continue;
                
                Ticket requiredTicket = Ticket.fromTransport(transport);
                if (requiredTicket == null) continue;
                
                int ticketCount = current.remainingTickets.getOrDefault(requiredTicket, 0);
                if (ticketCount <= 0) continue;
                
                Map<Ticket, Integer> newTickets = new HashMap<>(current.remainingTickets);
                newTickets.put(requiredTicket, ticketCount - 1);
                
                reachable.add(next);
                queue.offer(new State(next, newTickets, current.moves + 1));
            }
        }
        
        return reachable;
    }
    
    /**
     * Calculate mobility factor based on ticket diversity.
     * 
     * Returns 1.0-2.0 where:
     * - 1.0 = full mobility (all ticket types available)
     * - 2.0 = severely limited (only 1 ticket type)
     */
    private double calculateMobilityFactor(Map<Ticket, Integer> tickets) {
        int typesAvailable = 0;
        int totalTickets = 0;
        
        // Count ticket types (excluding SECRET and DOUBLE)
        for (Ticket t : new Ticket[]{Ticket.TAXI, Ticket.BUS, Ticket.UNDERGROUND}) {
            int count = tickets.getOrDefault(t, 0);
            if (count > 0) {
                typesAvailable++;
                totalTickets += count;
            }
        }
        
        if (typesAvailable == 0) {
            return 10.0; // Effectively immobile
        }
        
        // Mobility factor based on diversity
        if (typesAvailable == 3) {
            return 1.0; // Full mobility
        } else if (typesAvailable == 2) {
            return 1.3; // Moderate limitation
        } else {
            // Only 1 type - check if it's taxi (most common) or not
            if (tickets.getOrDefault(Ticket.TAXI, 0) > 0) {
                return 1.6; // Taxi-only is better than bus/underground-only
            } else {
                return 2.0; // Severely limited
            }
        }
    }
    
    /**
     * Calculate minimum effective distance from any detective to Mr. X.
     * This is the "true threat level" considering ticket constraints.
     */
    public double calculateMinEffectiveDistance(int mrXLocation, 
                                                 Set<Integer> detectiveLocations,
                                                 Map<Colour, Map<Ticket, Integer>> detectiveTickets) {
        double minEffectiveDist = Double.POSITIVE_INFINITY;
        
        for (int detLoc : detectiveLocations) {
            // Find which detective this is
            Map<Ticket, Integer> tickets = null;
            for (Map.Entry<Colour, Map<Ticket, Integer>> entry : detectiveTickets.entrySet()) {
                // This is a simplification - in real implementation, need to map location to colour
                tickets = entry.getValue();
                break; // Use first detective's tickets as approximation
            }
            
            if (tickets == null) {
                // Fallback to raw distance if no ticket info
                int rawDist = graphAnalyzer.calculateShortestPath(detLoc, mrXLocation);
                if (rawDist >= 0) {
                    minEffectiveDist = Math.min(minEffectiveDist, rawDist);
                }
            } else {
                double effectiveDist = calculateEffectiveDistance(detLoc, mrXLocation, tickets);
                minEffectiveDist = Math.min(minEffectiveDist, effectiveDist);
            }
        }
        
        return minEffectiveDist;
    }
    
    private static class State {
        final int location;
        final Map<Ticket, Integer> remainingTickets;
        final int moves;
        
        State(int location, Map<Ticket, Integer> remainingTickets, int moves) {
            this.location = location;
            this.remainingTickets = remainingTickets;
            this.moves = moves;
        }
    }
}

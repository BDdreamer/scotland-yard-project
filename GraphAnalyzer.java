package uk.ac.bris.cs.scotlandyard.ai;

import uk.ac.bris.cs.gamekit.graph.Edge;
import uk.ac.bris.cs.gamekit.graph.Graph;
import uk.ac.bris.cs.gamekit.graph.Node;
import uk.ac.bris.cs.scotlandyard.model.Ticket;
import uk.ac.bris.cs.scotlandyard.model.Transport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Analyzes the game graph to determine location safety and connectivity.
 * Provides strategic information for Mr. X's decision-making.
 *
 * v2 improvements:
 *  - findTransportBetween() is now public (required for detective ticket inference)
 *  - getAmbiguityScore()        : how many nodes can reach a destination (more = harder for detectives to narrow down)
 *  - getWeightedEffectiveExits(): softer, probabilistic exit count that avoids over-penalising contested exits
 */
public class GraphAnalyzer {

    private final Graph<Integer, Transport> graph;
    private final Map<Integer, Integer> connectivityScores;
    private final Map<Integer, Set<Integer>> adjacentLocations;

    // Pre-computed reverse adjacency: destination -> set of sources that can reach it
    private final Map<Integer, Set<Integer>> reverseAdjacency;

    // IMPROVED: Thread-safe cache for shortest-path BFS results (graph is immutable, so paths never change)
    // FIX: Was LinkedHashMap with access-order=true — causes infinite loops under parallel access.
    // ConcurrentHashMap is lock-free for reads. Max 199×199 ≈ 40K entries, so no eviction needed.
    private final java.util.concurrent.ConcurrentHashMap<Long, Integer> shortestPathCache;

    public GraphAnalyzer(Graph<Integer, Transport> graph) {
        this.graph = graph;
        this.connectivityScores = new HashMap<>();
        this.adjacentLocations = new HashMap<>();
        this.reverseAdjacency = new HashMap<>();
        
        // Thread-safe cache — no eviction needed (max ~40K pairs on 199-node graph)
        this.shortestPathCache = new java.util.concurrent.ConcurrentHashMap<>(8192, 0.75f, 4);
        
        analyzeGraph();
        identifyTransportHubs();
    }
    
    // PRIORITY 3: Transport Hub Identification
    private Set<Integer> transportHubs;
    
    /**
     * Identifies high-traffic transport hubs that humans naturally watch.
     * These are nodes with high total degree across all transport types.
     * 
     * Humans instinctively station detectives at:
     * - Major underground stations (sparse, high-value)
     * - High-degree bus junctions (many connections)
     * - Ferry terminals (unique transport type)
     * 
     * Threshold: degree > 5 across all transport types combined
     */
    private void identifyTransportHubs() {
        transportHubs = new HashSet<>();
        
        for (Node<Integer> node : graph.getNodes()) {
            int location = node.value();
            
            // Count total edges (degree) across all transport types
            int totalDegree = 0;
            Set<Transport> transportTypes = new HashSet<>();
            
            for (Edge<Integer, Transport> edge : graph.getEdgesFrom(node)) {
                totalDegree++;
                transportTypes.add(edge.data());
            }
            
            // Hub criteria:
            // 1. High degree (>5 connections) OR
            // 2. Underground station with 3+ connections (sparse but critical) OR
            // 3. Multiple transport types with 4+ connections
            boolean isHub = totalDegree > 5 ||
                           (transportTypes.contains(Transport.UNDERGROUND) && totalDegree >= 3) ||
                           (transportTypes.size() >= 3 && totalDegree >= 4);
            
            if (isHub) {
                transportHubs.add(location);
            }
        }
    }
    
    /**
     * Returns true if this location is a transport hub that humans watch.
     */
    public boolean isTransportHub(int location) {
        return transportHubs.contains(location);
    }
    
    /**
     * Returns all identified transport hubs.
     */
    public Set<Integer> getTransportHubs() {
        return new HashSet<>(transportHubs);
    }
    
    /**
     * Get high-value hubs within a certain distance of a target location.
     * Used for hub-blocking detective simulation in MCTS.
     * 
     * @param targetLocation The location to search around (typically Mr. X's position)
     * @param maxDistance Maximum distance to consider
     * @return Set of high-value hub locations within range
     */
    public Set<Integer> getHighValueHubs(int targetLocation, int maxDistance) {
        Set<Integer> nearbyHubs = new HashSet<>();
        
        for (int hub : transportHubs) {
            int dist = calculateShortestPath(hub, targetLocation);
            if (dist >= 0 && dist <= maxDistance) {
                nearbyHubs.add(hub);
            }
        }
        
        return nearbyHubs;
    }

    private void analyzeGraph() {
        for (Node<Integer> node : graph.getNodes()) {
            int location = node.value();
            Set<Integer> adjacent = new HashSet<>();
            int connectivity = 0;

            for (Edge<Integer, Transport> edge : graph.getEdgesFrom(node)) {
                int dest = edge.destination().value();
                adjacent.add(dest);
                connectivity++;

                // Build reverse map: dest <- location
                reverseAdjacency.computeIfAbsent(dest, k -> new HashSet<>()).add(location);
            }

            adjacentLocations.put(location, adjacent);
            connectivityScores.put(location, connectivity);
        }
    }

    // -------------------------------------------------------------------------
    // Basic accessors
    // -------------------------------------------------------------------------

    /** Number of outgoing edges from a location. Higher = more escape routes. */
    public int getConnectivity(int location) {
        return connectivityScores.getOrDefault(location, 0);
    }

    /** All locations directly reachable from the given location. */
    public Set<Integer> getAdjacentLocations(int location) {
        return adjacentLocations.getOrDefault(location, Collections.emptySet());
    }

    // -------------------------------------------------------------------------
    // Transport lookup  (was private - now public for ticket inference)
    // -------------------------------------------------------------------------

    /**
     * Returns the transport type on the edge from {@code from} to {@code to},
     * or {@code null} if no direct edge exists.
     * If multiple parallel edges exist (e.g. taxi AND bus between the same nodes)
     * the first one found is returned — callers that need all types should use
     * {@link #getAllTransportsBetween(int, int)}.
     */
    public Transport findTransportBetween(int from, int to) {
        Node<Integer> fromNode = graph.getNode(from);
        if (fromNode == null) return null;
        for (Edge<Integer, Transport> edge : graph.getEdgesFrom(fromNode)) {
            if (edge.destination().value() == to) return edge.data();
        }
        return null;
    }

    /**
     * Returns ALL transport types available on edges from {@code from} to {@code to}.
     * Useful when multiple parallel edges exist (e.g. taxi + bus on the same link).
     */
    public Set<Transport> getAllTransportsBetween(int from, int to) {
        Set<Transport> types = new HashSet<>();
        Node<Integer> fromNode = graph.getNode(from);
        if (fromNode == null) return types;
        for (Edge<Integer, Transport> edge : graph.getEdgesFrom(fromNode)) {
            if (edge.destination().value() == to) types.add(edge.data());
        }
        return types;
    }

    // -------------------------------------------------------------------------
    // NEW: Ambiguity scoring
    // -------------------------------------------------------------------------

    /**
     * Returns how many distinct nodes can reach {@code destination} in a single move.
     *
     * A high ambiguity score means that, from the detectives' perspective, many
     * possible "previous positions" lead to this destination — making it harder
     * for them to narrow down Mr. X's location after an unrevealed move.
     *
     * Used as a positive bonus in move evaluation.
     */
    public int getAmbiguityScore(int destination) {
        Set<Integer> sources = reverseAdjacency.get(destination);
        return sources == null ? 0 : sources.size();
    }

    /**
     * Ambiguity score normalised to [0, 1] relative to the most connected node
     * in the graph.  A value of 1.0 means this is the hardest destination to
     * pin-point; 0.0 means only one node can reach it.
     */
    public double getNormalisedAmbiguityScore(int destination) {
        if (reverseAdjacency.isEmpty()) return 0;
        int max = reverseAdjacency.values().stream().mapToInt(Set::size).max().orElse(1);
        return max == 0 ? 0 : (double) getAmbiguityScore(destination) / max;
    }

    // -------------------------------------------------------------------------
    // NEW: Weighted effective exits
    // -------------------------------------------------------------------------

    /**
     * Softer version of {@link #getEffectiveConnectivity} that avoids the
     * all-or-nothing problem of the binary blocked/unblocked model.
     *
     * Each exit is given a weight in [0, 1] based on how many detectives
     * can reach it in one move:
     *   0 threats  → weight 1.00  (open)
     *   1 threat   → weight 0.55  (contested)
     *   2 threats  → weight 0.20  (heavily contested)
     *   3+ threats → weight 0.05  (virtually closed)
     *   detective IS here → weight 0 (completely blocked)
     *
     * The return value is the sum of all exit weights, giving a fractional
     * "effective exit count" that is less pessimistic than the binary version
     * while still heavily penalising surrounded positions.
     */
    public double getWeightedEffectiveExits(int location, Set<Integer> detectiveLocations) {
        Set<Integer> exits = adjacentLocations.get(location);
        if (exits == null) return 0;

        double total = 0;
        for (int exit : exits) {
            if (detectiveLocations.contains(exit)) continue; // fully blocked

            int threats = 0;
            for (int detLoc : detectiveLocations) {
                if (getAdjacentLocations(detLoc).contains(exit)) threats++;
            }

            switch (threats) {
                case 0: total += 1.00; break;
                case 1: total += 0.55; break;
                case 2: total += 0.20; break;
                default: total += 0.05; break;
            }
        }
        return total;
    }

    // -------------------------------------------------------------------------
    // Escape volume
    // -------------------------------------------------------------------------

    /**
     * Measures the "Escape Volume" — how many nodes Mr. X can reach without
     * being intercepted by the collective "organism" of detectives.
     */
    public int getEscapeVolume(int location, Set<Integer> detectiveLocations, int depth) {
        Set<Integer> blocked = getCollectiveNodesWithinDistance(detectiveLocations, depth - 1);

        Set<Integer> reachable = new HashSet<>();
        Queue<int[]> queue = new LinkedList<>();
        queue.offer(new int[]{location, 0});
        reachable.add(location);

        while (!queue.isEmpty()) {
            int[] current = queue.poll();
            int loc = current[0];
            int d = current[1];
            if (d >= depth) continue;

            Set<Integer> adjacent = adjacentLocations.get(loc);
            if (adjacent != null) {
                for (int next : adjacent) {
                    if (!blocked.contains(next) && !reachable.contains(next)) {
                        reachable.add(next);
                        queue.offer(new int[]{next, d + 1});
                    }
                }
            }
        }
        return reachable.size();
    }

    private Set<Integer> getCollectiveNodesWithinDistance(Set<Integer> starts, int maxDist) {
        Set<Integer> nodes = new HashSet<>();
        Queue<int[]> q = new LinkedList<>();
        for (int start : starts) {
            q.offer(new int[]{start, 0});
            nodes.add(start);
        }
        while (!q.isEmpty()) {
            int[] curr = q.poll();
            if (curr[1] >= maxDist) continue;
            Set<Integer> adjacent = adjacentLocations.get(curr[0]);
            if (adjacent != null) {
                for (int adj : adjacent) {
                    if (!nodes.contains(adj)) {
                        nodes.add(adj);
                        q.offer(new int[]{adj, curr[1] + 1});
                    }
                }
            }
        }
        return nodes;
    }

    @SuppressWarnings("unused")
    private Set<Integer> getNodesWithinDistance(int start, int maxDist) {
        Set<Integer> nodes = new HashSet<>();
        Queue<int[]> q = new LinkedList<>();
        q.offer(new int[]{start, 0});
        nodes.add(start);
        while (!q.isEmpty()) {
            int[] curr = q.poll();
            if (curr[1] >= maxDist) continue;
            Set<Integer> adjacent = adjacentLocations.get(curr[0]);
            if (adjacent != null) {
                for (int adj : adjacent) {
                    if (!nodes.contains(adj)) {
                        nodes.add(adj);
                        q.offer(new int[]{adj, curr[1] + 1});
                    }
                }
            }
        }
        return nodes;
    }

    // -------------------------------------------------------------------------
    // Mobility scoring
    // -------------------------------------------------------------------------

    /**
     * Average connectivity of safe neighbours.
     * High values mean that even if one exit is blocked, many others remain.
     */
    public double getFutureMobilityScore(int location, Set<Integer> detectiveLocations) {
        Set<Integer> adjacent = adjacentLocations.get(location);
        if (adjacent == null || adjacent.isEmpty()) return 0;

        double totalFutureConn = 0;
        int validNeighbors = 0;

        for (int adj : adjacent) {
            if (!detectiveLocations.contains(adj)) {
                totalFutureConn += getEffectiveConnectivity(adj, detectiveLocations);
                validNeighbors++;
            }
        }
        return validNeighbors == 0 ? 0 : totalFutureConn / validNeighbors;
    }

    /**
     * How many exits from a location can be occupied by detectives in their next turn.
     */
    public int getContestedExits(int location, Set<Integer> detectiveLocations) {
        Set<Integer> exits = adjacentLocations.get(location);
        if (exits == null) return 0;

        int contested = 0;
        for (int exit : exits) {
            for (int detLoc : detectiveLocations) {
                if (detLoc == exit || getAdjacentLocations(detLoc).contains(exit)) {
                    contested++;
                    break;
                }
            }
        }
        return contested;
    }

    // -------------------------------------------------------------------------
    // Safety / danger scoring
    // -------------------------------------------------------------------------

    /**
     * Composite safety score for a location. Higher = safer.
     */
    public double calculateSafetyScore(int location, Set<Integer> detectiveLocations) {
        if (detectiveLocations.contains(location)) return -1000;

        // Use weighted exits for a less pessimistic connectivity assessment
        double weightedExits = getWeightedEffectiveExits(location, detectiveLocations);
        double safetyScore = weightedExits * 3.5;

        int detectivesClose = 0;
        double proximityPenalty = 0;

        for (int detectiveLoc : detectiveLocations) {
            int distance = calculateShortestPath(location, detectiveLoc);

            if (distance == -1) {
                safetyScore += 5;
            } else if (distance <= 2) {
                proximityPenalty += (3 - distance) * 15;
                detectivesClose++;
            } else if (distance <= 4) {
                proximityPenalty += (5 - distance) * 4;
            }
        }

        if (detectivesClose > 1) proximityPenalty *= 1.5;
        safetyScore -= proximityPenalty;

        // Penalise dead-ends when under pressure
        if (weightedExits <= 1.5 && detectivesClose > 0) safetyScore -= 50;

        return safetyScore;
    }

    // -------------------------------------------------------------------------
    // Shortest path
    // -------------------------------------------------------------------------

    /** Ticket-aware shortest path. Returns -1 if unreachable. */
    public int calculateShortestPath(int from, int to, Map<Ticket, Integer> tickets) {
        if (from == to) return 0;
        if (tickets == null) return calculateShortestPath(from, to);

        Queue<int[]> queue = new LinkedList<>();
        Map<Integer, Integer> visited = new HashMap<>();
        queue.offer(new int[]{from, 0});
        visited.put(from, 0);

        while (!queue.isEmpty()) {
            int[] current = queue.poll();
            int loc = current[0];
            int dist = current[1];

            for (Edge<Integer, Transport> edge : graph.getEdgesFrom(graph.getNode(loc))) {
                int next = edge.destination().value();
                Ticket required = Ticket.fromTransport(edge.data());
                if (tickets.getOrDefault(required, 0) > 0 && !visited.containsKey(next)) {
                    if (next == to) return dist + 1;
                    visited.put(next, dist + 1);
                    queue.offer(new int[]{next, dist + 1});
                }
            }
        }
        return -1;
    }

    /** Path with transport-type constraints; returns the node sequence or empty. */
    public List<Integer> calculatePathWithTransport(int from, int to, Map<Ticket, Integer> tickets) {
        if (from == to) return Collections.singletonList(from);
        if (tickets == null) return calculatePath(from, to);

        Queue<PathNode> queue = new LinkedList<>();
        Map<Integer, PathNode> visited = new HashMap<>();
        PathNode startNode = new PathNode(from, null, 0);
        queue.offer(startNode);
        visited.put(from, startNode);

        while (!queue.isEmpty()) {
            PathNode current = queue.poll();
            int loc = current.location;

            for (Edge<Integer, Transport> edge : graph.getEdgesFrom(graph.getNode(loc))) {
                int next = edge.destination().value();
                Ticket required = Ticket.fromTransport(edge.data());

                if (tickets.getOrDefault(required, 0) > 0 && !visited.containsKey(next)) {
                    PathNode nextNode = new PathNode(next, current, current.distance + 1);
                    if (next == to) {
                        List<Integer> path = new ArrayList<>();
                        PathNode node = nextNode;
                        while (node != null) { path.add(0, node.location); node = node.parent; }
                        return path;
                    }
                    visited.put(next, nextNode);
                    queue.offer(nextNode);
                }
            }
        }
        return Collections.emptyList();
    }

    private List<Integer> calculatePath(int from, int to) {
        if (from == to) return Collections.singletonList(from);

        Queue<PathNode> queue = new LinkedList<>();
        Map<Integer, PathNode> visited = new HashMap<>();
        PathNode startNode = new PathNode(from, null, 0);
        queue.offer(startNode);
        visited.put(from, startNode);

        while (!queue.isEmpty()) {
            PathNode current = queue.poll();
            int loc = current.location;

            Set<Integer> adjacent = adjacentLocations.get(loc);
            if (adjacent != null) {
                for (int next : adjacent) {
                    if (!visited.containsKey(next)) {
                        PathNode nextNode = new PathNode(next, current, current.distance + 1);
                        if (next == to) {
                            List<Integer> path = new ArrayList<>();
                            PathNode node = nextNode;
                            while (node != null) { path.add(0, node.location); node = node.parent; }
                            return path;
                        }
                        visited.put(next, nextNode);
                        queue.offer(nextNode);
                    }
                }
            }
        }
        return Collections.emptyList();
    }

    /** 
     * Standard BFS shortest path ignoring ticket constraints. Returns -1 if unreachable.
     * IMPROVED: results are cached with LRU eviction since the graph is immutable.
     * 
     * @param from Starting location
     * @param to Target location
     * @return Shortest path distance, or -1 if unreachable
     */
    public int calculateShortestPath(int from, int to) {
        if (from == to) return 0;

        // Check cache (ConcurrentHashMap — fully thread-safe)
        long cacheKey = ((long) from << 16) | (to & 0xFFFFL);
        Integer cached = shortestPathCache.get(cacheKey);
        if (cached != null) return cached;

        Set<Integer> visited = new HashSet<>();
        Queue<int[]> queue = new LinkedList<>();
        queue.offer(new int[]{from, 0});
        visited.add(from);
        
        // Add max iteration limit to prevent infinite loops (defensive)
        int maxIterations = graph.getNodes().size() * 2;
        int iterations = 0;

        while (!queue.isEmpty() && iterations < maxIterations) {
            iterations++;
            int[] current = queue.poll();
            int location = current[0];
            int distance = current[1];

            Set<Integer> adjacent = adjacentLocations.get(location);
            if (adjacent != null) {
                for (int next : adjacent) {
                    if (next == to) {
                        int result = distance + 1;
                        // Cache result (thread-safe put)
                        shortestPathCache.put(cacheKey, result);
                        return result;
                    }
                    if (!visited.contains(next)) {
                        visited.add(next);
                        queue.offer(new int[]{next, distance + 1});
                    }
                }
            }
        }

        // Cache negative result (unreachable)
        shortestPathCache.put(cacheKey, -1);
        return -1;
    }

    // -------------------------------------------------------------------------
    // Strategic destination evaluation
    // -------------------------------------------------------------------------

    /**
     * Overall strategic value of a destination.
     * Used for simple comparisons and in Monte Carlo rollout endpoints.
     */
    public double evaluateDestination(int destination, Set<Integer> detectiveLocations, boolean isRevealRound) {
        double score = calculateSafetyScore(destination, detectiveLocations);

        if (isRevealRound) score += connectivityScores.getOrDefault(destination, 0) * 3.0;

        Set<Integer> adjacent = adjacentLocations.get(destination);
        if (adjacent != null && adjacent.size() >= 4) score += 5;

        return score;
    }

    /**
     * Binary effective connectivity — number of exits NOT blocked by detectives
     * or reachable by detectives in one move.
     *
     * Kept for backward compatibility.  Prefer {@link #getWeightedEffectiveExits}
     * for scoring, as it is less pessimistic.
     */
    public int getEffectiveConnectivity(int location, Set<Integer> detectiveLocations) {
        Set<Integer> exits = adjacentLocations.get(location);
        if (exits == null) return 0;

        int effectiveExits = 0;
        for (int exit : exits) {
            boolean blocked = detectiveLocations.contains(exit);
            if (!blocked) {
                for (int detLoc : detectiveLocations) {
                    if (getAdjacentLocations(detLoc).contains(exit)) { blocked = true; break; }
                }
            }
            if (!blocked) effectiveExits++;
        }
        return effectiveExits;
    }

    /**
     * Detects if a location is in danger of being caught in a pincer movement.
     */
    public boolean isInPincerDanger(int location, Set<Integer> detectiveLocations) {
        if (detectiveLocations.size() < 2) return false;

        Set<Integer> exits = getAdjacentLocations(location);
        if (exits.isEmpty()) return true;

        int threatenedExits = 0;
        for (int exit : exits) {
            for (int detLoc : detectiveLocations) {
                int dist = calculateShortestPath(exit, detLoc);
                if (dist >= 0 && dist <= 2) { threatenedExits++; break; }
            }
        }

        double threatRatio = (double) threatenedExits / exits.size();
        return threatRatio > 0.6 && detectiveLocations.size() >= 2;
    }

    /**
     * All nodes reachable by ANY detective in one move (used for danger scoring).
     */
    public Set<Integer> predictDetectiveReach(Set<Integer> detectiveLocations, Graph<Integer, Transport> graph) {
        Set<Integer> reachable = new HashSet<>();
        for (int detLoc : detectiveLocations) {
            Set<Integer> adjacent = adjacentLocations.get(detLoc);
            if (adjacent != null) reachable.addAll(adjacent);
            reachable.add(detLoc);
        }
        return reachable;
    }

    /**
     * Danger level 0-100 based on predicted detective movements.
     */
    public double calculateDangerScore(int location, Set<Integer> detectiveLocations, Graph<Integer, Transport> graph) {
        double danger = 0;

        Set<Integer> detectiveReach = predictDetectiveReach(detectiveLocations, graph);
        if (detectiveReach.contains(location)) danger += 50;

        Set<Integer> nearbyLocations = getAdjacentLocations(location);
        int nearbyThreats = 0;
        for (int nearby : nearbyLocations) {
            if (detectiveReach.contains(nearby)) nearbyThreats++;
        }
        danger += nearbyThreats * 15;

        int detectivesAround = 0;
        for (int detLoc : detectiveLocations) {
            int dist = calculateShortestPath(location, detLoc);
            if (dist >= 0 && dist <= 2) detectivesAround++;
        }
        if (detectivesAround >= 3) danger += 40;

        return Math.min(danger, 100);
    }

    /**
     * Top N safest locations from a candidate set.
     */
    public List<Integer> getTopSafeLocations(Set<Integer> candidates, Set<Integer> detectiveLocations, int count) {
        return candidates.stream()
            .map(loc -> new AbstractMap.SimpleEntry<>(loc, evaluateDestination(loc, detectiveLocations, false)))
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .limit(count)
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Ticket-aware escape volume
    // -------------------------------------------------------------------------

    /**
     * Escape volume considering Mr. X's available tickets.
     */
    public int getTicketAwareEscapeVolume(int location, Set<Integer> detectiveLocations, int depth, Map<Ticket, Integer> tickets) {
        if (tickets == null || tickets.isEmpty()) return getEscapeVolume(location, detectiveLocations, depth);

        Set<Integer> blocked = getCollectiveNodesWithinDistance(detectiveLocations, depth - 1);

        Set<Integer> reachable = new HashSet<>();
        Queue<State> queue = new LinkedList<>();
        queue.offer(new State(location, new HashMap<>(tickets), 0));
        reachable.add(location);

        while (!queue.isEmpty()) {
            State current = queue.poll();
            if (current.distance >= depth) continue;

            Set<Integer> adjacent = adjacentLocations.get(current.location);
            if (adjacent == null) continue;

            for (int next : adjacent) {
                if (blocked.contains(next) || reachable.contains(next)) continue;

                Transport requiredTransport = findTransportBetween(current.location, next);
                if (requiredTransport == null) continue;

                Ticket requiredTicket = Ticket.fromTransport(requiredTransport);
                if (requiredTicket == null) continue;

                int ticketCount = current.remainingTickets.getOrDefault(requiredTicket, 0);
                if (ticketCount <= 0) continue;

                Map<Ticket, Integer> newTickets = new HashMap<>(current.remainingTickets);
                newTickets.put(requiredTicket, ticketCount - 1);

                reachable.add(next);
                queue.offer(new State(next, newTickets, current.distance + 1));
            }
        }
        return reachable.size();
    }

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    private static class PathNode {
        final int location;
        final PathNode parent;
        final int distance;

        PathNode(int location, PathNode parent, int distance) {
            this.location = location;
            this.parent = parent;
            this.distance = distance;
        }
    }

    private static class State {
        final int location;
        final Map<Ticket, Integer> remainingTickets;
        final int distance;

        State(int location, Map<Ticket, Integer> remainingTickets, int distance) {
            this.location = location;
            this.remainingTickets = remainingTickets;
            this.distance = distance;
        }
    }
    
    // -------------------------------------------------------------------------
    // Node feature export for clustering (k-means zone generation)
    // -------------------------------------------------------------------------
    
    /**
     * Exports node features to CSV for clustering analysis.
     * Run this once to generate node_features.csv, then use Python
     * k-means to generate zone_mapping.json.
     */
    public String exportNodeFeatures() {
        StringBuilder sb = new StringBuilder();
        sb.append("node,connectivity,taxiEdges,busEdges,underEdges\n");
        
        for (int node = 1; node <= 199; node++) {
            int taxi = 0, bus = 0, under = 0;
            Set<Integer> adjacents = adjacentLocations.get(node);
            if (adjacents == null) {
                sb.append(String.format("%d,0,0,0,0\n", node));
                continue;
            }
            for (int adj : adjacents) {
                Transport t = findTransportBetween(node, adj);
                if (t == Transport.TAXI) taxi++;
                else if (t == Transport.BUS) bus++;
                else if (t == Transport.UNDERGROUND) under++;
            }
            int connectivity = taxi + bus + under;
            sb.append(String.format("%d,%d,%d,%d,%d\n", 
                node, connectivity, taxi, bus, under));
        }
        
        return sb.toString();
    }
    
    /**
     * Exports all edges to CSV for network analysis.
     * Generates edges.csv with from,to,transport columns.
     * Directional edges - both directions included for Python adjacency list.
     */
    public String exportEdges() {
        StringBuilder sb = new StringBuilder();
        sb.append("from,to,transport\n");
        
        for (Node<Integer> node : graph.getNodes()) {
            int from = node.value();
            for (Edge<Integer, Transport> edge : graph.getEdgesFrom(node)) {
                int to = edge.destination().value();
                sb.append(String.format("%d,%d,%s\n", 
                    from, to, edge.data().name()));
            }
        }
        
        return sb.toString();
    }
}

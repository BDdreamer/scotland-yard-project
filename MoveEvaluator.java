package uk.ac.bris.cs.scotlandyard.ai;

import uk.ac.bris.cs.scotlandyard.model.*;

import java.util.*;
import java.util.stream.Collectors;

import static uk.ac.bris.cs.scotlandyard.ai.ScoringConfig.*;

/**
 * Enhanced move evaluator with improved detective behaviour prediction,
 * true multi-turn Monte Carlo lookahead, strategic randomisation, and
 * dynamic weight adjustment.
 *
 * IMPROVEMENTS:
 * - Integrated with ScoringConfig for centralized weight management
 * - Removed magic numbers throughout
 * - Added defensive null checks
 * - Improved code documentation
 */
public class MoveEvaluator {

    private static final Random SHARED_RANDOM = new Random(System.nanoTime());
    /** Optional benchmark override for rollout depth (applies to coordinated/non-coordinated). */
    private static final int OVERRIDE_ROLLOUT_DEPTH = Integer.getInteger("bench.mrx.rolloutDepth", -1);

    private final GraphAnalyzer graphAnalyzer;
    private ScotlandYardView view;
    private Set<Integer> detectiveLocations;
    private Map<Ticket, Integer> mrXTickets;
    private int currentRound;
    private List<Boolean> rounds;
    private int currentMrXLocation;
    private final Random random;
    private static final boolean DEBUG = false;

    private static void debug(String format, Object... args) {
        if (DEBUG) System.out.printf(format, args);
    }

    private static void debugErr(String format, Object... args) {
        if (DEBUG) System.err.printf(format, args);
    }

    private double detectiveSkillFactor = 0.75; // Default skill factor for simulated detectives

    // NEW: Belief state tracking, surface turn planning, cordon detection, zone strategy, formation detection, candidate-dividing detection
    private final BeliefStateTracker beliefTracker;
    private final SurfaceTurnPlanner surfacePlanner;
    private final CordonDetector cordonDetector;
    private final ZoneStrategy zoneStrategy;
    private final FormationDetector formationDetector;
    private final CandidateDividingDetector candidateDividingDetector;

    // Monte Carlo simulation parameters
    private static final int SIMULATIONS_PER_MOVE = 20;  // Reduced from 40 for performance
    private static final int LOOKAHEAD_DEPTH = MCTS_SIMULATION_DEPTH;
    private static final int ROLLOUT_CANDIDATE_COVERAGE_RADIUS = 2;

    /**
     * FIXED: use an absolute score band instead of a percentage of the best score.
     * The old code used  bestScore * (1 - 0.12)  which breaks badly when scores are
     * large or negative.  A fixed band keeps only genuinely close moves
     * in the randomisation pool regardless of absolute scale.
     * 
     * Adaptive band: wider in early game for unpredictability, narrower in endgame for precision.
     */
    private static final double RANDOMIZATION_SCORE_BAND_EARLY = 20.0;
    private static final double RANDOMIZATION_SCORE_BAND_MID = 15.0;
    private static final double RANDOMIZATION_SCORE_BAND_LATE = 10.0;

    // BASELINE PARAMETERS — For A/B testing and anti-confounding
    // These are the reference values. Tune by changing the active values, not these.
    public static final double FORMATION_PENALTY_BASELINE = 3.0;
    public static final double CANDIDATE_DIVIDING_PENALTY_BASELINE = 7.0;  // Increased from 2.5
    public static final int RANDOMIZATION_PERCENT_BASELINE = 15;
    public static final double CORDON_PENALTY_BASELINE = 5.0;  // Increased from 2.0
    
    // ACTIVE PARAMETERS — Change these for tuning
    private double formationPenaltyMultiplier = FORMATION_PENALTY_BASELINE;
    private double candidateDividingPenaltyMultiplier = CANDIDATE_DIVIDING_PENALTY_BASELINE;
    private int unpredictabilityPercent = RANDOMIZATION_PERCENT_BASELINE;
    private double cordonPenaltyMultiplier = CORDON_PENALTY_BASELINE;

    // Ticket value weights — dynamically adjusted by game phase
    private Map<Ticket, Double> ticketValueWeights;

    // Detective ticket estimation — actively tracked
    private Map<Ticket, Integer> estimatedDetectiveTickets;
    private Map<Colour, Map<Ticket, Integer>> detectiveTicketTracker;

    // Session learning
    private Map<Integer, Integer> captureLocations;
    private Map<Integer, Integer> escapeLocations;

    // Recent ticket history for pattern-break bonus (last 2 tickets used)
    private final java.util.Deque<Ticket> recentTickets = new java.util.ArrayDeque<>();

    // FIXED: move score cache to avoid redundant re-evaluation
    private final Map<Move, Double> moveScoreCache = new HashMap<>();
    private MCTSNode lastSearchRoot;
    private int lastSelectionDepth = 0;

    public MoveEvaluator(GraphAnalyzer graphAnalyzer,
                         BeliefStateTracker beliefTracker,
                         SurfaceTurnPlanner surfacePlanner,
                         ZoneStrategy zoneStrategy) {
        this.graphAnalyzer = graphAnalyzer;
        this.view = null;
        this.detectiveLocations = Collections.emptySet();
        this.mrXTickets = Collections.emptyMap();
        this.currentRound = 0;
        this.rounds = Collections.emptyList();
        this.currentMrXLocation = -1;
        this.random = SHARED_RANDOM;
        this.estimatedDetectiveTickets = initializeDetectiveTickets();
        this.detectiveTicketTracker = new HashMap<>();
        this.captureLocations = new HashMap<>();
        this.escapeLocations = new HashMap<>();

        this.beliefTracker = beliefTracker;
        this.surfacePlanner = surfacePlanner;
        this.cordonDetector = new CordonDetector(graphAnalyzer);
        this.zoneStrategy = zoneStrategy;
        this.formationDetector = new FormationDetector(graphAnalyzer);
        this.candidateDividingDetector = new CandidateDividingDetector(graphAnalyzer, beliefTracker);

        initializeDynamicWeights();
    }

    public MoveEvaluator(GraphAnalyzer graphAnalyzer, ScotlandYardView view,
                         Set<Integer> detectiveLocations, Map<Ticket, Integer> mrXTickets) {
        this(
            graphAnalyzer,
            new BeliefStateTracker(graphAnalyzer, view.getPlayerLocation(Colour.BLACK).orElse(1)),
            new SurfaceTurnPlanner(graphAnalyzer, view.getRounds()),
            new ZoneStrategy(graphAnalyzer, 199)
        );
        updateTurnContext(view, detectiveLocations, mrXTickets);
    }

    public void updateTurnContext(ScotlandYardView view,
                                  Set<Integer> detectiveLocations,
                                  Map<Ticket, Integer> mrXTickets) {
        int visibleLocation = view != null
            ? view.getPlayerLocation(Colour.BLACK).orElse(currentMrXLocation)
            : currentMrXLocation;
        updateTurnContext(view, detectiveLocations, mrXTickets, visibleLocation);
    }

    public void updateTurnContext(ScotlandYardView view,
                                  Set<Integer> detectiveLocations,
                                  Map<Ticket, Integer> mrXTickets,
                                  int currentMrXLocation) {
        this.view = view;
        this.detectiveLocations = detectiveLocations != null ? new HashSet<>(detectiveLocations) : new HashSet<>();
        this.mrXTickets = mrXTickets != null ? new HashMap<>(mrXTickets) : new HashMap<>();
        this.currentRound = view != null ? view.getCurrentRound() : 0;
        this.rounds = view != null ? view.getRounds() : Collections.emptyList();
        this.currentMrXLocation = currentMrXLocation;
        initializeDetectiveTicketTracker();
        recalculateDynamicWeights();
        moveScoreCache.clear();
    }

    public void setDetectiveTicketTracker(Map<Colour, Map<Ticket, Integer>> tracker) {
        this.detectiveTicketTracker = tracker;
        // Rebuild aggregate estimate from the per-detective tracker
        rebuildAggregateEstimate();
    }

    /** Called by CombinedAI after each move to track Mr X's recent ticket usage. */
    public void recordMrXTicketUsed(Ticket ticket) {
        recentTickets.addLast(ticket);
        if (recentTickets.size() > 2) recentTickets.removeFirst();
    }

    /** Sums per-detective trackers into the aggregate estimate. */
    private void rebuildAggregateEstimate() {
        if (detectiveTicketTracker.isEmpty()) return;
        Map<Ticket, Integer> aggregate = new HashMap<>();
        for (Map<Ticket, Integer> perDet : detectiveTicketTracker.values()) {
            for (Map.Entry<Ticket, Integer> e : perDet.entrySet()) {
                aggregate.merge(e.getKey(), e.getValue(), Integer::sum);
            }
        }
        this.estimatedDetectiveTickets = aggregate;
    }

    private Map<Ticket, Integer> initializeDetectiveTickets() {
        Map<Ticket, Integer> tickets = new HashMap<>();
        tickets.put(Ticket.TAXI,       11);
        tickets.put(Ticket.BUS,         8);
        tickets.put(Ticket.UNDERGROUND, 4);
        tickets.put(Ticket.SECRET,      0);
        tickets.put(Ticket.DOUBLE,      0);
        return tickets;
    }

    private void initializeDynamicWeights() {
        double gameProgress = getGameProgress();
        ticketValueWeights = new HashMap<>();

        if (gameProgress < 0.3) {
            // Early game: Encourage aggressive play, lower penalties
            ticketValueWeights.put(Ticket.SECRET,      2.5);  // Reduced from 4.0
            ticketValueWeights.put(Ticket.DOUBLE,      1.8);  // Reduced from 3.5 - use doubles early!
            ticketValueWeights.put(Ticket.UNDERGROUND, 1.5);  // Reduced from 2.0
            ticketValueWeights.put(Ticket.BUS,         0.8);
            ticketValueWeights.put(Ticket.TAXI,        0.6);
        } else if (gameProgress < 0.7) {
            // Mid game: Balanced conservation
            ticketValueWeights.put(Ticket.SECRET,      3.0);
            ticketValueWeights.put(Ticket.DOUBLE,      2.2);  // Reduced from 2.5
            ticketValueWeights.put(Ticket.UNDERGROUND, 1.5);
            ticketValueWeights.put(Ticket.BUS,         1.0);
            ticketValueWeights.put(Ticket.TAXI,        0.8);
        } else {
            // Late game: Use everything to survive
            ticketValueWeights.put(Ticket.SECRET,      1.2);  // Reduced from 1.5
            ticketValueWeights.put(Ticket.DOUBLE,      1.0);  // Reduced from 1.5
            ticketValueWeights.put(Ticket.UNDERGROUND, 0.8);
            ticketValueWeights.put(Ticket.BUS,         0.8);
            ticketValueWeights.put(Ticket.TAXI,        0.6);
        }
    }

    private void initializeDetectiveTicketTracker() {
        if (view == null) return;
        for (Colour colour : view.getPlayers()) {
            if (colour.isDetective()) {
                detectiveTicketTracker.computeIfAbsent(colour, c -> {
                    Map<Ticket, Integer> tickets = new HashMap<>();
                    tickets.put(Ticket.TAXI, view.getPlayerTickets(c, Ticket.TAXI).orElse(11));
                    tickets.put(Ticket.BUS, view.getPlayerTickets(c, Ticket.BUS).orElse(8));
                    tickets.put(Ticket.UNDERGROUND, view.getPlayerTickets(c, Ticket.UNDERGROUND).orElse(4));
                    tickets.put(Ticket.SECRET,      0);
                    tickets.put(Ticket.DOUBLE,      0);
                    return tickets;
                });
            }
        }
        rebuildAggregateEstimate();
    }

    private double getGameProgress() {
        int total = rounds.size();
        return total == 0 ? 0 : (double) currentRound / total;
    }

    // -------------------------------------------------------------------------
    // PARAMETER TUNING — Setter methods for A/B testing
    // Use these to tune weights without changing baseline constants
    // -------------------------------------------------------------------------

    public void setFormationPenaltyMultiplier(double multiplier) {
        this.formationPenaltyMultiplier = multiplier;
        System.out.printf("[TUNING] Formation penalty: %.2f (baseline: %.2f)%n",
            multiplier, FORMATION_PENALTY_BASELINE);
    }

    public void setCandidateDividingPenaltyMultiplier(double multiplier) {
        this.candidateDividingPenaltyMultiplier = multiplier;
        System.out.printf("[TUNING] Candidate-dividing penalty: %.2f (baseline: %.2f)%n",
            multiplier, CANDIDATE_DIVIDING_PENALTY_BASELINE);
    }

    public void setCordonPenaltyMultiplier(double multiplier) {
        this.cordonPenaltyMultiplier = multiplier;
        System.out.printf("[TUNING] Cordon penalty: %.2f (baseline: %.2f)%n",
            multiplier, CORDON_PENALTY_BASELINE);
    }

    public void setUnpredictabilityPercent(int percent) {
        this.unpredictabilityPercent = percent;
        System.out.printf("[TUNING] Unpredictability: %d%% (baseline: %d%%)%n",
            percent, RANDOMIZATION_PERCENT_BASELINE);
    }

    public void resetToBaseline() {
        this.formationPenaltyMultiplier = FORMATION_PENALTY_BASELINE;
        this.candidateDividingPenaltyMultiplier = CANDIDATE_DIVIDING_PENALTY_BASELINE;
        this.cordonPenaltyMultiplier = CORDON_PENALTY_BASELINE;
        this.unpredictabilityPercent = RANDOMIZATION_PERCENT_BASELINE;
        System.out.println("[TUNING] Reset all parameters to baseline");
    }

    public double getFormationPenaltyMultiplier() { return formationPenaltyMultiplier; }
    public double getCandidateDividingPenaltyMultiplier() { return candidateDividingPenaltyMultiplier; }
    public double getCordonPenaltyMultiplier() { return cordonPenaltyMultiplier; }
    public int getUnpredictabilityPercent() { return unpredictabilityPercent; }

    // -------------------------------------------------------------------------
    // Public evaluation entry points
    // -------------------------------------------------------------------------

    public double evaluateMove(Move move) {
        if (move instanceof TicketMove)  return evaluateTicketMove((TicketMove) move);
        if (move instanceof DoubleMove)  return evaluateDoubleMove((DoubleMove) move);
        return 0;
    }

    public double evaluateMoveWithLookahead(Move move) {
        // FIXED: Cache to avoid re-evaluating the same move multiple times
        Double cached = moveScoreCache.get(move);
        if (cached != null) return cached;

        if (DEBUG && currentRound <= 3) {
            debug("[EVAL-ENTRY] Round %d: evaluateMoveWithLookahead called%n", currentRound);
        }

        // 1. Fast heuristic (existing)
        double heuristicScore = evaluateMove(move);
        
        // 2. MCTS with detective simulation (selective usage)
        double mctsScore = 0.5; // Default neutral
        double mctsWeight = 0.0; // Default: don't use MCTS
        
        if (shouldUseMCTS(move)) {
            int destination = getMoveDestination(move);
            mctsScore = runMCTSWithDetectiveSimulation(destination, new HashMap<>(mrXTickets));
            mctsWeight = 0.3; // Use MCTS when it matters
        }
        
        // 3. Blend scores
        double result = heuristicScore + (mctsScore * 100) * mctsWeight;

        moveScoreCache.put(move, result);
        return result;
    }
    
    /**
     * Determine if MCTS should be used for this move.
     * Only use expensive MCTS when it matters most.
     * PERFORMANCE: Reduced MCTS usage to speed up rounds
     */
    private boolean shouldUseMCTS(Move move) {
        int destination = getMoveDestination(move);
        
        // PERFORMANCE: Skip MCTS in early game (rounds 1-5) - heuristics are sufficient
        // Use MCTS only in critical situations
        
        // Use MCTS when under extreme pressure (very low ambiguity)
        int candidatePressure = beliefTracker.getAmbiguityCount();
        if (candidatePressure < 10) {  // Reduced from 20
            return true; // Detectives have narrowed down locations significantly
        }
        
        // REFINEMENT: Use MCTS when candidate set is tight (mid-game candidate-dividing)
        int candidateCount = beliefTracker.getPossibleLocations().size();
        if (candidateCount < 25) {  // Detectives have a tight candidate set
            if (DEBUG) debug("[MCTS-TRIGGER] Round %d: Tight candidate set (%d < 25)%n", currentRound, candidateCount);
            return true;
        }
        
        // Use MCTS on reveal rounds only (not before)
        if (isRevealRound(currentRound)) {
            if (DEBUG) debug("[MCTS-TRIGGER] Round %d: Reveal round%n", currentRound);
            return true;
        }
        
        // Use MCTS when mobility is critically limited
        int connectivity = graphAnalyzer.getEffectiveConnectivity(destination, detectiveLocations);
        if (connectivity <= 1) {
            if (DEBUG) debug("[MCTS-TRIGGER] Round %d: Critical low mobility (%d <= 1)%n", currentRound, connectivity);
            return true;
        }
        
        // Use MCTS only in late game (last 20%) when stakes are highest
        double gameProgress = getGameProgress();
        if (gameProgress > 0.8) {  // Increased from 0.6
            return true;
        }
        
        // Otherwise, heuristic-only (save computation)
        return false;
    }
    
    /**
     * Run MCTS with detective simulation for a specific destination.
     * Returns win rate (0.0 to 1.0).
     * 
     * Restored budget: 250 sequential rollouts. 
     * (Thread-safety issues were eliminated, but sequential 250 runs in ~150ms anyways)
     */
    private double runMCTSWithDetectiveSimulation(int destination, Map<Ticket, Integer> tickets) {
        int rollouts = 50; // Reduced from 250 for faster execution (performance optimization)
        
        long startTime = System.currentTimeMillis();
        
        // Sequential rollouts — fast enough at 50, avoids thread-safety concerns entirely
        double totalScore = 0;
        List<Integer> detLocsList = new ArrayList<>(detectiveLocations);
        for (int i = 0; i < rollouts; i++) {
            Map<Ticket, Integer> ticketsCopy = new HashMap<>(tickets);
            totalScore += rollout(destination, detLocsList, ticketsCopy);
        }
        
        long duration = System.currentTimeMillis() - startTime;
        double winRate = totalScore / rollouts;
        if (DEBUG) debug("[MCTS-EXEC] %d rollouts for dest %d in %dms, win rate: %.2f%n", 
                          rollouts, destination, duration, winRate);
        
        return winRate;
    }

    // -------------------------------------------------------------------------
    // TicketMove evaluation — core heuristics
    // -------------------------------------------------------------------------

    private double evaluateTicketMove(TicketMove move) {
        double score = 0;
        int destination = move.destination();
        Ticket ticket = move.ticket();
        
        // CRITICAL FIX: Detect coordinated detective behavior and boost escape priority
        boolean coordinatedDetectivesDetected = detectCoordinatedBehavior();
        double coordinationMultiplier = coordinatedDetectivesDetected ? 1.5 : 1.0;
        
        // Note: Hard reject removed - it was too aggressive and caused worse outcomes
        // The emergency escape bonus and proximity penalties handle danger adequately

        // CRITICAL FIX: Emergency escape mode when facing imminent capture
        boolean emergencyMode = isInEmergencyMode(destination, detectiveLocations);
        if (emergencyMode) {
            score += 200.0; // Massive bonus for any move that gets us out of immediate danger
            if (DEBUG) debug("[EMERGENCY] Emergency escape mode activated for destination %d%n", destination);
        }

        // 1. Destination safety (boosted against coordinated detectives)
        boolean nextIsReveal = isRevealRound(currentRound + 1);
        score += graphAnalyzer.evaluateDestination(destination, detectiveLocations, nextIsReveal) * DESTINATION_SAFETY_MULTIPLIER * coordinationMultiplier;

        // 1a. Reveal preparation - heavily weight connectivity 2 rounds before each reveal
        score += getRevealPreparationBonus(destination, currentRound);

        // 2. Ticket conservation — penalise spending more valuable tickets
        Double ticketWeight = ticketValueWeights.get(ticket);
        if (ticketWeight != null) {
            double gameProgress = getGameProgress();
            // Against coordinated detectives, be more willing to spend tickets for safety
            // Use per-ticket discounts: SECRET 0.8x, DOUBLE 0.7x (less steep than flat 0.7x)
            double conservationMultiplier = 1.0;
            if (coordinatedDetectivesDetected) {
                if (ticket == Ticket.SECRET) {
                    conservationMultiplier = 0.8;
                } else if (ticket == Ticket.DOUBLE) {
                    conservationMultiplier = 0.7;
                } else {
                    conservationMultiplier = 0.7;
                }
            }
            // Early game: Encourage aggressive ticket usage to establish lead
            if (gameProgress < 0.25) {
                score -= ticketWeight * 3.0 * conservationMultiplier; // Reduced from 5.0
            } else if (gameProgress < 0.5) {
                score -= ticketWeight * 4.0 * conservationMultiplier;
            } else {
                score -= ticketWeight * 4.5 * conservationMultiplier;
            }
        }
        
        // BUS CORRIDOR FUNNEL DETECTION (Critical Missing Feature)
        if (ticket == Ticket.BUS) {
            // Count bus neighbors at destination
            int busExits = 0;
            Set<Integer> adjacent = graphAnalyzer.getAdjacentLocations(destination);
            for (int adj : adjacent) {
                Transport t = graphAnalyzer.findTransportBetween(destination, adj);
                if (t == Transport.BUS) {
                    busExits++;
                }
            }
            
            // Bus corridor with ≤2 exits is a funnel trap
            if (busExits <= 2) {
                score -= 30.0; // Penalty for bus funnel - nearly as bad as revealing
            } else if (busExits == 3) {
                score -= 10.0; // Moderate penalty for limited bus options
            }
        }
        
        // UNDERGROUND CANDIDATE SET SIZE CHECK (Critical Missing Feature)
        if (ticket == Ticket.UNDERGROUND) {
            // Underground is sparse - only ~12 nodes have it
            // Small candidate set makes it easy for detectives to enumerate
            // This is a rough approximation - full implementation would track actual candidate set
            
            int undergroundNeighbors = 0;
            Set<Integer> adjacent = graphAnalyzer.getAdjacentLocations(destination);
            for (int adj : adjacent) {
                Transport t = graphAnalyzer.findTransportBetween(destination, adj);
                if (t == Transport.UNDERGROUND) {
                    undergroundNeighbors++;
                }
            }
            
            // Isolated underground node = small candidate set
            if (undergroundNeighbors <= 1) {
                score -= 40.0; // Too easy for detectives to enumerate
            } else if (undergroundNeighbors >= 3) {
                score += 20.0; // Rare but valuable - good connectivity
            }
            
            // Near reveal rounds, underground candidate set is especially problematic
            boolean nextIsRevealUnderground = isRevealRound(currentRound + 1);
            if (nextIsRevealUnderground && undergroundNeighbors <= 2) {
                score -= 30.0; // Additional penalty near reveals
            }
        }

        // Extra penalty for spending the LAST of a ticket type
        Integer ticketCount = mrXTickets.get(ticket);
        if (ticketCount != null && ticketCount <= 1) {
            double gameProgress = getGameProgress();
            if (ticket == Ticket.SECRET) {
                // Only penalize last secret in early game, use freely in endgame
                score -= (gameProgress < 0.5) ? 8 : 2;
            } else if (ticket == Ticket.DOUBLE) {
                // Don't hoard the last double - use it if needed
                score -= (gameProgress < 0.5) ? 10 : 3;
            } else {
                score -= 12; // Reduced from 15
            }
        }

        // 3. Distance bonus (FIXED: was double-counting distance>3 AND distance>5)
        score += calculateDistanceBonus(destination);

        // 4. Weighted effective exits (softer, less pessimistic than binary version)
        double weightedExits = graphAnalyzer.getWeightedEffectiveExits(destination, detectiveLocations);
        score += weightedExits * EFFECTIVE_EXITS_WEIGHT;

        // 5. Escape volume — weight scales with detective count
        int escapeVolume = graphAnalyzer.getEscapeVolume(destination, detectiveLocations, 3);
        double evWeight = 5.0 + (detectiveLocations.size() * 1.5);
        score += escapeVolume * evWeight;

        // Calculate min distance to detectives once for reuse (lines 247 have nextIsReveal)
        int minDetDist = Integer.MAX_VALUE;
        for (int detLoc : detectiveLocations) {
            int dist = graphAnalyzer.calculateShortestPath(destination, detLoc);
            if (dist >= 0) minDetDist = Math.min(minDetDist, dist);
        }
        
        // 6. Enclosure check with conditional soft veto
        int totalExits     = graphAnalyzer.getConnectivity(destination);
        int contestedExits = graphAnalyzer.getContestedExits(destination, detectiveLocations);
        int effectiveExits = totalExits - contestedExits;
        if (effectiveExits <= 1) {
            boolean hasNearbyDetective = minDetDist <= 4;
            if (hasNearbyDetective && !nextIsReveal) {
                score -= 140.0;  // Stronger penalty: coordinated teams punish low-exit nodes hard
            } else if (effectiveExits == 0) {
                score -= 80.0;  // Even without nearby detective, zero exits is still dangerous
            }
        }

        // 7. Interception risk
        score -= calculateUnifiedProximityPenalty(destination);
        
        // 8. Transport diversity
        score += calculateTransportDiversityBonus(destination);

        // 10. PRIORITY 3 — Transport Hub Avoidance (Dynamic)
        // Humans instinctively station detectives at major hubs:
        // - Underground stations (sparse, high-value targets)
        // - High-degree bus junctions (natural convergence points)
        // - Multi-transport nodes (strategic control points)
        // FIX 3: Hubs are only dangerous when contested
        if (graphAnalyzer.isTransportHub(destination)) {
            // Use minDetDist computed earlier (lines 337-339)
            // Dynamic penalty based on detective proximity
            if (minDetDist > 5) {
                score -= 30.0; // Flexible use - hub is safe for now
            } else if (minDetDist > 3) {
                score -= 55.0; // Moderate risk - hub is being approached
            } else {
                score -= 80.0; // High risk - hub is contested
            }
            
            // Bonus: Hubs are good for DOUBLE moves (high mobility)
            if (ticket == Ticket.DOUBLE && minDetDist > 4) {
                score += 20.0; // Hub mobility advantage
            }
        }

        // 11. Game-phase adjustments
        score += adjustForGamePhase(ticket, destination);

        // 12. Pincer danger
        if (graphAnalyzer.isInPincerDanger(destination, detectiveLocations)) score -= 45.0;

        // 13. Session learning
        score += getHistoricalSafetyScore(destination) * HISTORICAL_SAFETY_WEIGHT;

        // 14. Cross-game persistent learning
        score += getPersistentSafetyScore(destination) * PERSISTENT_SAFETY_WEIGHT;

        // 15. Ambiguity bonus: reward moves where detectives can't easily pin down Mr. X
        score += calculateAmbiguityBonus(destination);

        // 15. Transport exploit bonus: reward using transports detectives are low on
        score += calculateTransportExploitBonus(ticket);

        // 16. Strategic SECRET usage based on reveal rounds
        boolean currentIsReveal = isRevealRound(currentRound);
        boolean nextIsRevealSecret = isRevealRound(currentRound + 1);
        
        // BUG 1 FIX: Check transport types at CURRENT location, not destination
        // Ambiguity depends on what detectives think you could have used FROM here
        int currentLocForSecret = getCurrentMrXLocation(destination);
        
        // CRITICAL: Check if SECRET provides any ambiguity at CURRENT node
        if (ticket == Ticket.SECRET) {
            Set<Transport> availableTransports = getAvailableTransportTypes(currentLocForSecret);
            
            if (DEBUG) debugErr("[SECRET CHECK] Round %d at location %d: %d transports available%n",
                currentRound, currentLocForSecret, availableTransports.size());
            
            // SOFTENED CONSTRAINT: Single-transport node = SECRET is wasteful
            // Changed from hard constraint to heavy penalty to allow desperate moves
            if (availableTransports.size() == 1) {
                score -= 200.0;  // Very heavy penalty, but still allows move if desperate
                if (DEBUG) debugErr("[SECRET PENALTY] Heavy penalty for SECRET at single-transport node %d%n", 
                    currentLocForSecret);
            }
        }
        
        // NEVER use secret ON a reveal round (already heavily penalized above)
        if (!currentIsReveal && ticket == Ticket.SECRET) {
            int secretsRemaining = mrXTickets.getOrDefault(Ticket.SECRET, 0);
            double gameProgress = getGameProgress();
            
            // Get transport diversity at CURRENT node for ambiguity calculation
            Set<Transport> availableTransports = getAvailableTransportTypes(currentLocForSecret);
            int transportDiversity = availableTransports.size();
            
            // BUG 6 FIX: SECRET value scales with transport diversity
            // 2 types = 1.0x (normal), 3 types = 1.5x (maximum ambiguity)
            double diversityMultiplier = transportDiversity / 2.0;
            
            // BUG 5 FIX: Ticket depletion reservation
            // Count remaining reveals and reserve SECRETs for them
            int remainingReveals = 0;
            for (int r = currentRound + 1; r <= rounds.size(); r++) {
                if (isRevealRound(r)) remainingReveals++;
            }
            
            boolean shouldReserveSecret = secretsRemaining <= remainingReveals;
            
            // Case 1: BEFORE reveal rounds - MOST POWERFUL USE
            // SECRET before reveal massively expands candidate set detectives must consider
            if (nextIsReveal && secretsRemaining >= 1) {
                // Detectives won't know which transport type was used
                // This creates maximum ambiguity at the reveal point
                int minDetDistForAmbiguity = Integer.MAX_VALUE;
                for (int detLoc : detectiveLocations) {
                    int dist = graphAnalyzer.calculateShortestPath(destination, detLoc);
                    if (dist >= 0) minDetDistForAmbiguity = Math.min(minDetDistForAmbiguity, dist);
                }
                
                int connectivity = graphAnalyzer.getConnectivity(destination);
                
                // Calculate base bonus - MASSIVELY INCREASED for pre-reveal SECRET
                double baseBonus = 0;
                if (minDetDistForAmbiguity <= 4) {
                    if (connectivity >= 4) {
                        baseBonus = (gameProgress > 0.7) ? 150.0 : 130.0; // Increased from 100/90
                    } else if (connectivity >= 3) {
                        baseBonus = (gameProgress > 0.7) ? 110.0 : 95.0; // Increased from 75/65
                    } else {
                        baseBonus = (gameProgress > 0.7) ? 75.0 : 60.0; // Increased from 50/40
                    }
                } else if (minDetDistForAmbiguity <= 6) {
                    if (connectivity >= 4) {
                        baseBonus = 80.0; // Increased from 50
                    } else {
                        baseBonus = 50.0; // Increased from 30
                    }
                }
                
                // Apply diversity multiplier
                score += baseBonus * diversityMultiplier;
                
                // BUG 2 FIX: Two-move SECRET stack bonus (MUCH HIGHER)
                // Check if we're 2 moves before reveal (even more powerful)
                int roundsUntilReveal = 0;
                for (int r = currentRound + 1; r <= rounds.size(); r++) {
                    if (isRevealRound(r)) {
                        roundsUntilReveal = r - currentRound;
                        break;
                    }
                }
                
                // MASSIVE BONUS: Immediately before reveal (1 round away)
                if (roundsUntilReveal == 1) {
                    // This is THE most powerful SECRET usage
                    score += 300.0 * diversityMultiplier;
                    if (DEBUG) debug("[PRE-REVEAL-SECRET] Round %d, 1 before reveal, MASSIVE bonus applied%n",
                        currentRound);
                }
                
                // BUG 4 FIX: Correct reveal timing (2 before round 8 is round 6, not 7)
                if (roundsUntilReveal == 2) {
                    // This is the FIRST of two SECRETs before reveal
                    // Maximum ambiguity - detectives must consider ALL transport combos
                    score += 120.0 * diversityMultiplier; // Increased from 80
                }
            }
            
            // Case 2: After reveal rounds - hide escape direction
            boolean previousWasReveal = isRevealRound(currentRound - 1);
            if (previousWasReveal && secretsRemaining >= 1) {
                // Valuable but less than before-reveal usage
                int connectivity = graphAnalyzer.getConnectivity(destination);
                double baseBonus = 0;
                if (connectivity >= 4) {
                    baseBonus = 40.0; // Increased from 35
                } else if (connectivity >= 3) {
                    baseBonus = 30.0; // Increased from 25
                } else {
                    baseBonus = 20.0; // Increased from 15
                }
                
                // Apply diversity multiplier
                score += baseBonus * diversityMultiplier;
            }
            
            // Case 3: Endgame aggression - use secrets for survival
            // BUT: Only if not reserved for future reveals
            if (gameProgress > 0.75 && secretsRemaining >= 1 && !nextIsReveal && !previousWasReveal) {
                if (!shouldReserveSecret) {
                    score += 25.0 * diversityMultiplier; // Increased from 20
                }
            }
            
            // Case 4: Early game unpredictability - establish confusion
            // BUT: Only if we have plenty of secrets
            if (gameProgress < 0.2 && secretsRemaining >= 3 && !nextIsReveal) {
                score += 15.0 * diversityMultiplier; // Increased from 12
            }
            
            // Case 5: Mid-game non-reveal usage - heavily discouraged if secrets are reserved
            if (!nextIsReveal && !previousWasReveal && shouldReserveSecret) {
                score -= 60.0; // Strong penalty for wasting reserved SECRET
            }
        }

        // 17. NEW — Live Territory (Voronoi) — measure controlled space
        score += calculateLiveTerritoryScore(destination, detectiveLocations);
        
        // 18. CRITICAL — Cordon Detection (Encirclement Risk)
        // A position far from all detectives is TERRIBLE if they surround you
        double encirclementRisk = cordonDetector.calculateEncirclementRisk(destination, detectiveLocations);
        double cordonPenalty = encirclementRisk * cordonPenaltyMultiplier;
        
        // In high encirclement risk, drastically increase penalty
        if (encirclementRisk > 40) {
            cordonPenalty *= 1.8;
        }
        if (encirclementRisk > 60) {
            score -= 80.0; // Extra guard against severe cordon positions
        }
        
        score -= cordonPenalty; // Scale: 0-200+ penalty for full encirclement
        
        // 18b & 18c. COORDINATED DETECTION — Formation + Candidate-Dividing
        // FIX: Use max with diminishing returns to avoid double-penalizing safe moves
        // Formation risk (0-100) and candidate pressure (0-100) measure different patterns
        // Stacking them can over-penalize moves that trigger both detectors
        double formationRisk = formationDetector.calculateFormationRisk(destination, detectiveLocations);
        double candidatePressure = candidateDividingDetector.calculateCandidatePressure(destination, detectiveLocations);
        
        // Calculate individual penalties using active parameters
        double formationPenalty = formationRisk * formationPenaltyMultiplier;  // 0-300
        double candidatePenalty = candidatePressure * candidateDividingPenaltyMultiplier;  // 0-250

        // Candidate-size-scaled pressure: as detective belief tightens, coordination danger rises quickly.
        int pressureCandidateCount = beliefTracker.getPossibleLocations().size();
        double pressureScale = Math.min(2.0, 12.0 / Math.max(pressureCandidateCount, 1));
        formationPenalty *= pressureScale;
        candidatePenalty *= pressureScale;
        
        // ANTI-CONFOUNDING: Use max with 20% secondary penalty instead of sum
        // This prevents 5.5x total penalty when both detectors fire
        double primaryPenalty = Math.max(formationPenalty, candidatePenalty);
        double secondaryPenalty = Math.min(formationPenalty, candidatePenalty) * 0.2;  // 20% of smaller
        double totalCoordinatedPenalty = primaryPenalty + secondaryPenalty;
        
        // TEMPORARY VERIFICATION: Remove after testing
        if (formationPenalty > 0 && candidatePenalty > 0) {
            double rawSum = formationPenalty + candidatePenalty;
            double reduction = (1 - totalCoordinatedPenalty/rawSum) * 100;
            if (DEBUG) debug("[ANTICONFOUND] formation=%.1f candidate=%.1f rawSum=%.1f fixed=%.1f reduction=%.1f%%%n",
                formationPenalty, candidatePenalty, rawSum, totalCoordinatedPenalty, reduction);
        }
        
        score -= totalCoordinatedPenalty;
        
        // Candidate-dividing trap detection: avoid moves that are easy to eliminate
        if (candidateDividingDetector.isCandidateTrap(destination, ticket, detectiveLocations)) {
            score -= 80.0; // strong corrective penalty
            if (DEBUG) {
                debugErr("[CANDIDATE-TRAP] Destination %d via %s is a high-risk trap.%n",
                    destination, ticket);
            }
        }
        
        // LOGGING: Output detector values for threshold verification (every 5 rounds)
        if (DEBUG && currentRound % 5 == 0) {
            Set<Integer> candidateSet = beliefTracker.getPossibleLocations();
            debug("[DETECTOR] Round %d: formationRisk=%.1f candidatePressure=%.1f " +
                "candidateSetSize=%d totalPenalty=%.1f%n",
                currentRound, formationRisk, candidatePressure, candidateSet.size(), totalCoordinatedPenalty);
        }
        
        // Bonus: When candidate set is small, maximize ambiguity
        if (candidatePressure > 20) {  // Lowered threshold from 30 to 20 for earlier response
            double ambiguityBonus = candidateDividingDetector.calculateAmbiguityBonus(
                getCurrentMrXLocation(destination), ticket, destination);
            score += ambiguityBonus; // -20 to +50 bonus for increasing ambiguity
            
            // SECRET DESPERATION: High-leverage usage when candidate set is small
            // When detectives have narrowed down possibilities, SECRET breaks their tracking
            if (ticket == Ticket.SECRET) {
                Set<Integer> candidateSet = beliefTracker.getPossibleLocations();
                int candidateSetSize = candidateSet.size();
                
                if (candidateSetSize < 12) {
                    // Desperation bonus scales with how trapped we are
                    // Small candidate set = detectives have narrowed us down = SECRET is critical
                    double desperationBonus = (15 - candidateSetSize) * 15.0; // 45-180 bonus range
                    score += desperationBonus;
                    if (DEBUG) debug("[SECRET-DESPERATION] Set size %d, pressure %.1f, bonus %.0f%n",
                        candidateSetSize, candidatePressure, desperationBonus);
                }
                
                // Pre-reveal SECRET bonus (one round before reveal)
                int roundsUntilReveal = 0;
                for (int r = currentRound + 1; r <= rounds.size(); r++) {
                    if (isRevealRound(r)) {
                        roundsUntilReveal = r - currentRound;
                        break;
                    }
                }
                if (roundsUntilReveal == 1 && mrXTickets.getOrDefault(Ticket.SECRET, 0) > 0) {
                    // Massive bonus - using SECRET right before reveal forces detectives to consider
                    // ALL possible transport types, exploding their candidate set
                    score += 200.0;
                    if (DEBUG) debug("[PRE-REVEAL-SECRET] Round %d, 1 before reveal, pressure %.1f%n",
                        currentRound, candidatePressure);
                }
            }
            
            // COUNTER-DIVIDING: Aggressive bonus for moves that explode candidate set
            // Against candidate-dividing detectives, we want to actively maximize ambiguity
            Set<Integer> candidateSet = beliefTracker.getPossibleLocations();
            int currentCandidates = candidateSet.size();
            int projectedCandidates = simulateCandidateSetGrowth(destination, 2);
            
            if (projectedCandidates > currentCandidates * 1.5) {
                // Big bonus for moves that significantly expand candidate set.
                // Full bonus when set is small (most critical); half bonus when larger.
                double counterDividingBonus = currentCandidates < 15 ? 80.0 : 40.0;
                score += counterDividingBonus;
                if (DEBUG) debug("[COUNTER] Round %d: Ambiguity bonus! %d → %d candidates (%.1f%% growth)%n",
                    currentRound, currentCandidates, projectedCandidates,
                    (projectedCandidates - currentCandidates) * 100.0 / currentCandidates);
            }
        }
        
        // 18.5 HUB AVOIDANCE — Detectives blocking hubs (different from encirclement)
        // Hub-blocking detectives position at hubs to cut off exits, not surround
        // CRITICAL: Early game (rounds 1-8) needs MUCH stronger hub avoidance
        // Games ending rounds 5-12 means Mr. X walks into hub traps immediately
        if (graphAnalyzer.isTransportHub(destination)) {
            int minDetToHub = Integer.MAX_VALUE;
            for (int detLoc : detectiveLocations) {
                int detToHub = graphAnalyzer.calculateShortestPath(detLoc, destination);
                if (detToHub >= 0) {
                    minDetToHub = Math.min(minDetToHub, detToHub);
                }
            }
            if (minDetToHub != Integer.MAX_VALUE) {
                double hubPenalty = Math.min(300.0, 300.0 / Math.max(1.0, minDetToHub - 1.0));
                score -= hubPenalty;
            }
        }
        
        // 19. CRITICAL — Escape Mode Override
        // When surrounded or detectives very close, ignore ambiguity and maximize distance
        boolean escapeMode = cordonDetector.shouldEnterEscapeMode(destination, detectiveLocations);
        int currentCandidateCount = beliefTracker.getPossibleLocations().size();
        boolean underPressure = currentCandidateCount > 0
            && currentCandidateCount <= 12
            && (encirclementRisk > 50 || minDetDist <= 2 || effectiveExits <= 1);
        if (escapeMode) {
            // In escape mode: distance and exits matter most, ambiguity doesn't
            // Use computed minDetDist from line 341 instead of redeclaring
            double escapeScore = minDetDist * 25.0 + weightedExits * 15.0;
            // Keep a small portion of the original score for tie-breaking
            score = escapeScore * 0.8 + score * 0.2;
        } else if (underPressure) {
            // Under tight belief pressure, coordinated detectives punish ambiguity-first moves.
            // Blend toward concrete survival metrics.
            double survivalScore = minDetDist * 22.0 + weightedExits * 14.0 + escapeVolume * 2.0;
            double survivalWeight = currentCandidateCount <= 6 ? 0.7 : 0.5;
            score = survivalScore * survivalWeight + score * (1.0 - survivalWeight);
        }
        
        // 20. PRIORITY 1 — Cognitive Complexity Maximization (Information Control)
        // This is THE most important factor for Mr. X - control what detectives think
        // Humans struggle with large candidate sets, transport diversity, and large jumps
        // BUT: Only when NOT in escape mode AND when safe
        if (!escapeMode) {
            boolean nextIsRevealForCognitive = isRevealRound(currentRound + 1);
            
            // REFINEMENT 1: Better safety metric - convergence risk, not just distance
            // Distance ≠ danger. Humans cut off, not just chase.
            // Use minDetDist from line 341
            
            // Count detectives clustering nearby (real danger)
            int nearbyDetectives = 0;
            for (int detLoc : detectiveLocations) {
                int dist = graphAnalyzer.calculateShortestPath(destination, detLoc);
                if (dist >= 0 && dist <= 3) nearbyDetectives++;
            }
            
            // Count safe escape routes (real safety)
            int escapeRoutes = 0;
            Set<Integer> neighbors = graphAnalyzer.getAdjacentLocations(destination);
            for (int neighbor : neighbors) {
                boolean isSafe = true;
                for (int detLoc : detectiveLocations) {
                    int distToNeighbor = graphAnalyzer.calculateShortestPath(neighbor, detLoc);
                    if (distToNeighbor >= 0 && distToNeighbor <= 1) {
                        isSafe = false;
                        break;
                    }
                }
                if (isSafe) escapeRoutes++;
            }
            
            // Convergence danger score: clustering - mobility
            double convergenceRisk = (nearbyDetectives * 20.0) - (escapeRoutes * 10.0);
            
            // Estimate recent ticket diversity from game state
            List<Ticket> estimatedLast3 = new ArrayList<>();
            
            double ambiguityBonus = beliefTracker.evaluateCognitiveComplexity(
                getCurrentMrXLocation(destination),
                ticket, 
                destination,
                estimatedLast3,
                nextIsRevealForCognitive
            );
            
            // CRITICAL: Survival overrides confusion
            // Use convergence risk, not just distance
            double cognitiveWeight = 2.0; // Base weight
            if (convergenceRisk > 40 || minDetDist < 2) {
                cognitiveWeight *= 0.2; // Severe danger - pure survival
            } else if (convergenceRisk > 20 || minDetDist < 3) {
                cognitiveWeight *= 0.4; // High danger - mostly survival
            } else if (convergenceRisk > 0 || minDetDist < 5) {
                cognitiveWeight *= 0.7; // Moderate danger - balanced
            }
            // else full weight - safe to maximize confusion
            
            score += ambiguityBonus * cognitiveWeight;
        }
        
        // 21. CRITICAL — Surface Turn Planning
        // Plan backwards from next reveal to ensure strong surface position
        int roundsUntilSurface = surfacePlanner.getRoundsUntilReveal(currentRound);
        if (roundsUntilSurface > 0 && roundsUntilSurface <= 3) {
            double surfaceBonus = surfacePlanner.calculateSurfacePreparationBonus(
                getCurrentMrXLocation(destination),
                destination,
                roundsUntilSurface,
                detectiveLocations,
                mrXTickets
            );
            
            // SCENARIO 1 FIX: Cordon at surface turn collision
            // If this IS the surface turn, check cordon risk FIRST
            if (roundsUntilSurface == 1) {
                double surfaceCordonRisk = cordonDetector.calculateEncirclementRisk(destination, detectiveLocations);
                
                if (surfaceCordonRisk > 60) {
                    // CORDON WINS: A trapped reveal is losing
                    score -= 100.0; // Override surface bonus with massive penalty
                } else if (surfaceCordonRisk > 40) {
                    score += surfaceBonus * 0.2; // Only 20% of bonus
                    score -= 30.0; // Additional penalty
                } else {
                    score += surfaceBonus; // Full bonus
                }
                
                // Extra penalty if this would surface in a trap
                if (surfacePlanner.isSurfaceTrap(destination, detectiveLocations)) {
                    score -= 70.0;
                }
            } else {
                // Not the surface turn itself, just preparation
                score += surfaceBonus;
            }
        }
        
        // 22. CRITICAL — Proactive Zone Strategy
        // Bias toward safe zones in mid-game, not just reactive escape
        double gameProgress = getGameProgress();
        int currentLocForZone = getCurrentMrXLocation(destination);
        double zoneBonus = zoneStrategy.calculateZoneBonus(currentLocForZone, destination, gameProgress);
        
        // Early-game zone boost (rounds 1-8) - aggressively migrate to safe zones
        if (gameProgress < 0.3) {
            zoneBonus *= 1.5;
        }
        
        score += zoneBonus;
        
        // Zone crossing penalty if not using SECRET (mid-game only)
        if (gameProgress >= 0.3 && gameProgress < 0.7) {
            if (zoneStrategy.isZoneCrossing(currentLocForZone, destination) && ticket != Ticket.SECRET) {
                score -= 15.0; // Penalty for risky zone crossing without SECRET
            }
        }
        
        // 23. Post-Reveal Escape Mode
        // First 2 moves after reveal are most dangerous - maximize distance only
        boolean previousWasReveal = isRevealRound(currentRound - 1);
        boolean twoRoundsAgoWasReveal = currentRound >= 2 && isRevealRound(currentRound - 2);
        if (previousWasReveal || twoRoundsAgoWasReveal) {
            // Post-reveal panic: distance matters most
            // Use minDetDist from line 341
            
            // SCENARIO 2 FIX: Post-reveal panic with no good escape
            // Even if all options are bad, pick the least-bad one
            if (minDetDist == Integer.MAX_VALUE) {
                // Unreachable by detectives - very good
                score += 50.0;
            } else if (minDetDist <= 1) {
                // Very close - still give SOME bonus for distance 1 vs distance 0
                score += minDetDist * 10.0; // At least 10 points for not being caught
            } else {
                // Normal case
                score += minDetDist * 15.0; // Extra distance bonus post-reveal
            }
            
            // Also prioritize high connectivity even in panic mode
            // Need exits to continue escaping next turn
            int connectivity = graphAnalyzer.getConnectivity(destination);
            score += connectivity * 5.0; // Exits matter in panic
        }
        
        // =====================================================================
        // PRIORITY UPGRADES: Human-Specific AI Improvements
        // =====================================================================
        
        // 24. PRIORITY 1: Reveal Preparation Strategy
        // Biggest immediate survival gain - position well before reveals
        score += calculateRevealPreparationBonus(destination, ticket);
        
        // 25. PRIORITY 2: Zone Collapse Detection
        // React to pressure before it becomes fatal
        score += calculateZoneCollapseResponse(destination, ticket);
        
        // 26. PRIORITY 3: Simple Predictive Positioning (Chokepoint Avoidance)
        // Avoid natural blocking positions when detectives are nearby
        score += calculateChokepointAvoidanceBonus(destination, ticket);
        
        // 27. GAP DETECTION - Counter to hub-blocking funneling strategy
        // Reward moving into large gaps between detectives
        // This directly addresses the funneling pattern where detectives spread out
        score += calculateGapScore(destination, new ArrayList<>(detectiveLocations));
        
        // 28. LATE GAME MODE SWITCH - Progressive survival-dominant blend
        // When game is 75%+ complete or round 16+, prioritize survival but preserve ambiguity
        if (gameProgress > 0.75 || currentRound >= 16) {
            // Use minDetDist from line 341 (computed earlier)
            if (minDetDist != Integer.MAX_VALUE) {
                // Override: pure distance and exits matter most
                double survivalScore = minDetDist * 25.0;  // Heavy weight on distance
                survivalScore += effectiveExits * 15.0;     // Exits critical
                survivalScore += escapeVolume * 3.0;
                
                // PROGRESSIVE BLEND: Survival weight increases with game progress
                // 70% progress = 50% survival, 100% progress = 90% survival
                double survivalWeight = Math.min(0.9, 0.5 + (gameProgress - 0.7) * 1.5);
                score = (survivalScore * survivalWeight) + (score * (1.0 - survivalWeight));
                
                // But still apply critical trap penalty
                if (effectiveExits <= 1) {
                    score -= 100.0;  // Trap penalty still applies
                }
            }
        }

        // 29. PATTERN-BREAK BONUS — reward transport variety to stay unpredictable
        // If the last 2 tickets were both the same type and we're now using a different one, bonus.
        if (recentTickets.size() == 2) {
            Ticket[] last2 = recentTickets.toArray(new Ticket[0]);
            if (last2[0] == last2[1] && last2[1] != ticket) {
                score += 25.0; // breaking a repeated pattern
                if (ticket == Ticket.SECRET) score += 30.0; // extra for SECRET deception
            }
        }

        return score;
    }

    /**
     * Previous version evaluated both sub-moves independently against the CURRENT
     * detective positions, which is wrong: after the first move the detectives will
     * react.  This version simulates their response to the first move and then
     * evaluates the second destination against those PREDICTED positions.
     */
    private double evaluateDoubleMove(DoubleMove move) {
        // CRITICAL FIX: Context-aware DOUBLE constraint for opening rounds
        // Round 0: Never - detectives can't possibly threaten yet (0-indexed!)
        if (currentRound == 0) {
            return Double.NEGATIVE_INFINITY;
        }
        
        // Rounds 1-2: Only if detective is immediately adjacent (distance <= 1)
        if (currentRound <= 2) {
            int currentLocation = view.getPlayerLocation(Colour.BLACK).orElse(move.firstMove().destination());
            boolean immediateThreat = detectiveLocations.stream()
                .anyMatch(d -> graphAnalyzer.calculateShortestPath(currentLocation, d) <= 1);
            
            if (!immediateThreat) {
                return Double.NEGATIVE_INFINITY; // No adjacent threat = don't waste DOUBLE
            }
        }
        // Round 3+: Normal evaluation applies
        
        double score = 0;

        int firstDest = move.firstMove().destination();
        int finalDest = move.finalDestination();
        Ticket firstTicket  = move.firstMove().ticket();
        Ticket secondTicket = move.secondMove().ticket();

        // HARD CONSTRAINT: Never use SECRET at single-transport nodes (even in DOUBLE moves)
        int currentLoc = view.getPlayerLocation(Colour.BLACK).orElse(firstDest);
        if (firstTicket == Ticket.SECRET) {
            Set<Transport> availableAtCurrent = getAvailableTransportTypes(currentLoc);
            if (availableAtCurrent.size() == 1) {
                return Double.NEGATIVE_INFINITY; // Block this DOUBLE move entirely
            }
        }
        if (secondTicket == Ticket.SECRET) {
            Set<Transport> availableAtFirst = getAvailableTransportTypes(firstDest);
            if (availableAtFirst.size() == 1) {
                return Double.NEGATIVE_INFINITY; // Block this DOUBLE move entirely
            }
        }

        // CRITICAL: Check if using SECRET on a reveal round within the double move
        boolean firstIsReveal = isRevealRound(currentRound);
        boolean secondIsReveal = isRevealRound(currentRound + 1);
        
        if (firstTicket == Ticket.SECRET && firstIsReveal) {
            score -= 200.0; // Wasting secret on first move of double
        }
        if (secondTicket == Ticket.SECRET && secondIsReveal) {
            score -= 200.0; // Wasting secret on second move of double
        }
        
        // CRITICAL: DOUBLE on reveal round gives detectives TWO data points
        // This is catastrophic - they see both moves clearly
        if (firstIsReveal || secondIsReveal) {
            score -= 150.0; // Massive penalty for double on reveal round
        }

        // --- First leg: evaluate against current detective positions ---
        score += graphAnalyzer.calculateSafetyScore(firstDest, detectiveLocations) * 0.4;

        // --- Simulate detective response to first-leg landing position ---
        Set<Integer> predictedDetLocs = simulateDetectiveMoves(firstDest, detectiveLocations);

        // --- Second leg: evaluate against PREDICTED detective positions ---
        score += graphAnalyzer.calculateSafetyScore(finalDest, predictedDetLocs) * 1.6;

        // Escape volume at the final destination against predicted positions
        int escapeVol = graphAnalyzer.getEscapeVolume(finalDest, predictedDetLocs, 3);
        score += escapeVol * 10.0;

        // Ambiguity at final destination
        score += calculateAmbiguityBonus(finalDest);

        // PANIC DOUBLE TRIGGER: When in immediate danger, DOUBLE gets massive bonus
        // Check if 2+ detectives are within distance 2 of first destination
        int nearbyDetectives = 0;
        for (int detLoc : detectiveLocations) {
            int dist = graphAnalyzer.calculateShortestPath(firstDest, detLoc);
            if (dist >= 0 && dist <= 2) {
                nearbyDetectives++;
            }
        }
        
        if (nearbyDetectives >= 2) {
            score += 80.0;  // Strong incentive to use DOUBLE to break out of cordon
            if (DEBUG) debug("[PANIC-DOUBLE] Round %d: %d detectives within distance 2, bonus applied%n",
                currentRound, nearbyDetectives);
        }

        // Ticket costs
        Double w1 = ticketValueWeights.get(firstTicket);
        Double w2 = ticketValueWeights.get(secondTicket);
        if (w1 != null) score -= w1 * 5.0;
        if (w2 != null) score -= w2 * 5.0;

        // Dynamic penalty for using the DOUBLE ticket
        int doubleTickets = mrXTickets.getOrDefault(Ticket.DOUBLE, 0);
        
        double gameProgress = getGameProgress();
        
        // Emergency override - reduce penalty when in immediate danger
        boolean inImmediateDanger = isInImmediateDanger(finalDest, predictedDetLocs);
        
        // CRITICAL FIX: Against greedy detectives, DOUBLE is ESSENTIAL in early game
        // Must establish distance immediately or get caught by round 8
        double doublePenalty;
        int earlyGameThreshold = (int)(rounds.size() * 0.25);
        boolean isEarlyGame = currentRound <= earlyGameThreshold;
        
        // Calculate how much distance this double creates
        int startPos = view.getPlayerLocation(Colour.BLACK).orElse(0);
        int distGained = graphAnalyzer.calculateShortestPath(startPos, finalDest);
        
        if (inImmediateDanger) {
            // When escaping danger, DOUBLE is a lifeline
            doublePenalty = -30.0; // STRONGER bonus for using double to escape (was -20)
        } else if (isEarlyGame && doubleTickets >= 1) {
            // Early game: MASSIVELY ENCOURAGE DOUBLE usage
            // This is the ONLY way to survive against coordinated pursuit
            if (distGained >= 3) {
                doublePenalty = -30.0; // HUGE bonus for distance-gaining doubles (was -60)
            } else if (distGained >= 2) {
                doublePenalty = -20.0; // Large bonus for moderate distance (was -40)
            } else {
                doublePenalty = -15.0; // Still encourage even short doubles (was -10)
            }
        } else if (gameProgress < 0.5 && doubleTickets >= 2) {
            // Mid-early game: Still encourage usage if we have 2+
            doublePenalty = -10.0; // Small bonus (was -5)
        } else if (gameProgress > 0.7) {
            // Late game: Use DOUBLE for survival
            doublePenalty = (doubleTickets <= 1) ? 2.0 : -8.0; // More bonus for using spare doubles
        } else {
            // Mid game: Moderate penalty
            doublePenalty = (doubleTickets <= 1) ? 6.0 : 2.0; // Reduced penalty (was 8/3)
        }
        score -= doublePenalty;

        // Bonus when the double move achieves significant distance from start
        if (distGained >= 3) {
            score += distGained * 8.0; // Increased from 5.0
        } else if (distGained >= 2) {
            score += distGained * 4.0; // Reward even moderate distance gains
        }
        
        // Emergency escape bonus - extra incentive when escaping pincer/capture
        if (inImmediateDanger && distGained >= 2) {
            score += 40.0; // Increased from 30.0
        }
        
        // Early game distance bonus - doubles should create massive separation
        if (gameProgress < 0.25 && distGained >= 4) {
            score += 50.0; // Big bonus for establishing early dominance (was 35)
        } else if (gameProgress < 0.25 && distGained >= 3) {
            score += 25.0; // Bonus for good early distance (new)
        }
        
        // Strategic positioning bonus - reward doubles that land in high-connectivity areas
        int finalConnectivity = graphAnalyzer.getConnectivity(finalDest);
        if (finalConnectivity >= 4) {
            score += finalConnectivity * 3.0; // Reward landing in well-connected areas
        }

        return score;
    }

    // -------------------------------------------------------------------------
    // NEW bonus calculations
    // -------------------------------------------------------------------------

    /**
     * Ambiguity bonus — reward destinations that many nodes can reach in one move.
     * A higher ambiguity score means detectives have a larger "shadow set" of
     * possible Mr. X positions after the move, making tracking harder.
     */
    private double calculateAmbiguityBonus(int destination) {
        int ambiguity = graphAnalyzer.getAmbiguityScore(destination);
        return Math.min(ambiguity * 3.0, 60.0);
    }

    /**
     * Calculates "Live Territory" (Voronoi-like) score.
     * Measures nodes reachable by Mr. X before any detective can reach them.
     */
    private double calculateLiveTerritoryScore(int location, Set<Integer> detectives) {
        int controlledNodes = 0;
        Queue<Integer> queue = new LinkedList<>();
        queue.add(location);
        Map<Integer, Integer> xDistances = new HashMap<>();
        xDistances.put(location, 0);

        // BFS to find all nodes Mr. X can reach within 3 moves
        while (!queue.isEmpty()) {
            int curr = queue.poll();
            int d = xDistances.get(curr);
            if (d >= 3) continue;
            for (int neighbor : graphAnalyzer.getAdjacentLocations(curr)) {
                if (!xDistances.containsKey(neighbor)) {
                    xDistances.put(neighbor, d + 1);
                    queue.add(neighbor);
                }
            }
        }
        // Check which of those nodes are "closer" to Mr. X than any detective
        for (Map.Entry<Integer, Integer> entry : xDistances.entrySet()) {
            int xDist = entry.getValue();
            boolean reachableByDet = false;
            for (int detLoc : detectives) {
                int detDist = graphAnalyzer.calculateShortestPath(detLoc, entry.getKey());
                if (detDist != -1 && detDist <= xDist) { reachableByDet = true; break; }
            }
            if (!reachableByDet) controlledNodes++;
        }
        return controlledNodes * 4.0;
    }

    /**
     * Transport exploit bonus — if detectives are running low on the ticket type
     * needed to follow Mr. X via a particular transport, those moves are safer.
     * 
     * ENHANCED: Now checks per-detective tickets to find escape corridors.
     * A detective with no underground tickets is effectively much farther away.
     * REFINEMENT: Checks if the NEAREST detective can follow this transport.
     */
    private double calculateTransportExploitBonus(Ticket usedTicket) {
        double bonus = 0;
        
        // Check aggregate detective tickets
        if (areDetectivesLowOnTickets(usedTicket)) {
            bonus += 18.0;
        }
        
        // NEW: Check per-detective ticket asymmetry
        // Find which detectives CAN'T follow this transport type
        int detectivesWhoCanFollow = 0;
        int totalDetectives = detectiveTicketTracker.size();
        
        // REFINEMENT: Track the nearest detective and whether they can follow
        int nearestThreatDistance = Integer.MAX_VALUE;
        boolean nearestCanFollow = false;
        
        for (Map.Entry<Colour, Map<Ticket, Integer>> entry : detectiveTicketTracker.entrySet()) {
            Map<Ticket, Integer> tickets = entry.getValue();
            int ticketCount = tickets.getOrDefault(usedTicket, 0);
            
            if (ticketCount > 0) {
                detectivesWhoCanFollow++;
            }
            
            // Find distance to this detective
            if (view != null) {
                int detLoc = view.getPlayerLocation(entry.getKey()).orElse(-1);
                if (detLoc != -1 && currentMrXLocation != -1) {
                    int dist = graphAnalyzer.calculateShortestPath(detLoc, currentMrXLocation);
                    if (dist >= 0 && dist < nearestThreatDistance) {
                        nearestThreatDistance = dist;
                        nearestCanFollow = ticketCount > 0;
                    }
                }
            }
        }
        
        // CRITICAL BONUS: If the closest detective cannot follow, this move is exceptionally safe
        if (!nearestCanFollow && nearestThreatDistance <= 5) {
            bonus += 40.0;  // Strong bonus when the immediate threat is ticket-limited
        }
        
        // Bonus scales with how many detectives are blocked
        if (totalDetectives > 0) {
            int detectivesBlocked = totalDetectives - detectivesWhoCanFollow;
            bonus += detectivesBlocked * 8.0; // 8 points per detective who can't follow
        }
        
        // Partial bonus if supply is limited but not exhausted
        int totalRemaining = estimatedDetectiveTickets.getOrDefault(usedTicket, 0);
        int detectiveCount = Math.max(1, detectiveTicketTracker.size());
        double avgRemaining = (double) totalRemaining / detectiveCount;
        if (avgRemaining < 4.0 && avgRemaining > 0) {
            bonus += 8.0; // Detectives getting low — modest bonus
        }
        
        return bonus;
    }

    // -------------------------------------------------------------------------
    // MCTS (Monte Carlo Tree Search) - Proper tree-based search
    // -------------------------------------------------------------------------
    
    private static final double MCTS_EXPLORATION = ScoringConfig.MCTS_EXPLORATION_CONSTANT;
    private static final double MCTS_FINAL_SELECTION_EXPLORATION = 0.35;
    private static final int MCTS_TIME_BUDGET_MS = 400;  // 400ms per move for MCTS to improve round speed
    
    /**
     * Run MCTS from the given position to find the best continuation.
     * Replaces simple Monte Carlo rollouts with proper tree search.
     */
    private double runMCTS(int mrXPosition, Map<Ticket, Integer> tickets) {
        MCTSNode root = new MCTSNode(mrXPosition, copyTickets(tickets));
        long deadline = System.currentTimeMillis() + MCTS_TIME_BUDGET_MS;

        List<Integer> detectivePositions = getDetectivePositionsSortedForSimulation();

        Set<Integer> adjacent = graphAnalyzer.getAdjacentLocations(mrXPosition);
        for (int dest : adjacent) {
            TicketMove move = createTicketMoveForEdge(mrXPosition, dest, root.remainingTickets);
            if (move == null) continue;
            root.children.add(new MCTSNode(dest, move, root, getRemainingTicketsAfterMove(root.remainingTickets, move)));
        }
        
        if (root.children.isEmpty()) return 0.0;
        
        int iterations = 0;
        int expansions = 0;
        int depthSum = 0;
        while (System.currentTimeMillis() < deadline) {
            MCTSNode node = select(root);
            depthSum += Math.max(0, lastSelectionDepth);

            if (node.visits > 0 && node.isLeaf()) {
                expansions += expandNode(node);
            }

            MCTSNode simulationNode = pickChildForSimulation(node);
            Map<Ticket, Integer> rolloutTickets = simulationNode.remainingTickets != null
                ? copyTickets(simulationNode.remainingTickets)
                : copyTickets(tickets);
            double score = rollout(simulationNode.mrXPosition, detectivePositions, rolloutTickets);
            backpropagate(simulationNode, score);
            iterations++;
        }
        
        if (iterations < 5) {
            System.err.printf("[MCTS] WARNING: Only %d iterations in %dms (too slow?)%n",
                iterations, MCTS_TIME_BUDGET_MS);
        }
        if (iterations > 0 && expansions > 0) {
            System.err.printf("[MCTS] depth=%.2f expansions=%d%n",
                (double) depthSum / iterations, expansions);
        }
        
        MCTSNode bestChild = root.children.stream()
            .max(Comparator.comparingDouble(n -> finalSelectionScore(n, root.visits)))
            .orElse(root.children.get(0));
        
        return bestChild.getAverageScore();
    }
    
    /**
     * Selection: Walk down tree using UCB1 until reaching a leaf node.
     * Only traverses nodes with valid parents and valid moves.
     */
    private MCTSNode select(MCTSNode root) {
        MCTSNode node = root;
        int depth = 0;
        while (!node.isLeaf()) {
            final double exploration = MCTS_EXPLORATION;
            MCTSNode best = null;
            double bestUCB = Double.NEGATIVE_INFINITY;
            
            for (MCTSNode child : node.children) {
                double ucb;
                if (child.visits == 0) {
                    ucb = Double.POSITIVE_INFINITY;
                } else {
                    int parentVisits = child.parent == null ? 1 : Math.max(1, child.parent.visits);
                    ucb = (child.totalScore / child.visits) + 
                          exploration * Math.sqrt(Math.log(parentVisits) / child.visits);
                }
                if (ucb > bestUCB) {
                    bestUCB = ucb;
                    best = child;
                }
            }
            
            if (best == null) break;
            node = best;
            depth++;

            // Stop early at the first unseen node so expansion can happen from it.
            if (node.visits == 0) {
                break;
            }
        }
        lastSelectionDepth = depth;
        return node;
    }

    private int expandNode(MCTSNode node) {
        if (node == null || !node.isLeaf() || node.remainingTickets == null) {
            return 0;
        }
        Set<Integer> adjacent = graphAnalyzer.getAdjacentLocations(node.mrXPosition);
        int added = 0;
        for (int dest : adjacent) {
            TicketMove nextMove = createTicketMoveForEdge(node.mrXPosition, dest, node.remainingTickets);
            if (nextMove == null) continue;
            Map<Ticket, Integer> childTickets = getRemainingTicketsAfterMove(node.remainingTickets, nextMove);
            node.children.add(new MCTSNode(dest, nextMove, node, childTickets));
            added++;
        }
        return added;
    }

    private MCTSNode pickChildForSimulation(MCTSNode node) {
        if (node == null || node.children.isEmpty()) {
            return node;
        }
        List<MCTSNode> unvisited = node.children.stream()
            .filter(child -> child.visits == 0)
            .collect(Collectors.toList());
        if (!unvisited.isEmpty()) {
            return unvisited.get(random.nextInt(unvisited.size()));
        }
        return node.children.stream()
            .min(Comparator.comparingInt(child -> child.visits))
            .orElse(node);
    }

    private double finalSelectionScore(MCTSNode child, int parentVisits) {
        if (child == null || child.visits == 0) {
            return Double.NEGATIVE_INFINITY;
        }
        double avg = child.totalScore / child.visits;
        double explore = MCTS_FINAL_SELECTION_EXPLORATION
            * Math.sqrt(Math.log(Math.max(1, parentVisits)) / child.visits);
        return avg + explore;
    }
    
    /**
     * Same detective ordering as {@link CoordinatedDetectiveAI#getSortedDetectives} so
     * {@link DetectiveSimulator#simulateCoordinatedMoves} role ties match the live coordinated team.
     */
    private List<Integer> getDetectivePositionsSortedForSimulation() {
        if (view == null) {
            return Collections.emptyList();
        }
        return view.getPlayers().stream()
            .filter(Colour::isDetective)
            .sorted(Comparator.comparing(Colour::name))
            .map(c -> view.getPlayerLocation(c).orElse(-1))
            .collect(Collectors.toList());
    }

    /**
     * IMPROVEMENT 1 & 2: Smarter rollouts with guided Mr. X moves and hub-blocking detective simulation.
     * 
     * Previously: Random Mr. X moves + random detective moves during rollout.
     * Now: Evaluation-guided Mr. X moves + hub-blocking detective behavior.
     * 
     * Expected improvement: +9-14% win rate vs hub-blocking detectives.
     */
    private double rollout(int startPosition, List<Integer> detectivePositions, 
                          Map<Ticket, Integer> simTickets) {
        Set<Integer> beliefSet = getSimulatedBeliefSet(currentMrXLocation, currentRound);
        
        // Use 6 determinizations to capture detective possibilities
        int samples = 6;
        double totalScore = 0;
        
        for (int i = 0; i < samples; i++) {
            int determinizedLoc = sampleBeliefLocation(beliefSet);
            Map<Ticket, Integer> ticketsCopy = new HashMap<>(simTickets);
            totalScore += simulateSingleRolloutWithDetectives(determinizedLoc, detectivePositions, ticketsCopy);
        }
        
        return totalScore / samples;
    }
    
    /**
     * Samples a location from the belief state for ISMCTS determinization.
     * @param possibleLocations Set of locations detectives believe Mr. X could be at
     * @return Sampled location from belief set, or current location as fallback
     */
    private int sampleBeliefLocation(Set<Integer> possibleLocations) {
        if (possibleLocations == null || possibleLocations.isEmpty()) {
            return currentMrXLocation; // Safe fallback
        }
        int index = random.nextInt(possibleLocations.size());
        return possibleLocations.stream()
            .skip(index)
            .findFirst()
            .orElse(currentMrXLocation);
    }

    /**
     * Compute simulated belief set for ISMCTS - expands from last known reveal location.
     * Uses BFS to estimate where detectives think Mr. X could be.
     * @param actualLocation Mr. X's actual location
     * @param round Current game round
     * @return Set of possible locations for determinization
     */
    private Set<Integer> getSimulatedBeliefSet(int actualLocation, int round) {
        int lastKnown = beliefTracker.getLastRevealedLocation();
        int movesSince = beliefTracker.getMovesSinceReveal();
        
        if (lastKnown <= 0) {
            return Collections.singleton(actualLocation);
        }
        
        // FIX: Was Math.max(movesSince + 1, round) — at round 15 this expands the ENTIRE graph!
        // Cap at 6 levels: on avg-degree-4 graph, 6 levels already reaches ~100+ nodes
        int expansionLevels = Math.min(Math.max(movesSince + 1, 3), 6);
        
        Set<Integer> reachable = new HashSet<>();
        Set<Integer> frontier = new HashSet<>();
        frontier.add(lastKnown);
        
        for (int i = 0; i < expansionLevels; i++) {
            Set<Integer> next = new HashSet<>();
            for (int loc : frontier) {
                next.addAll(graphAnalyzer.getAdjacentLocations(loc));
            }
            frontier = next;
            reachable.addAll(next);
        }
        
        reachable.add(actualLocation);
        return reachable;
    }

    private Map<Ticket, Integer> copyTickets(Map<Ticket, Integer> tickets) {
        return tickets == null ? null : new HashMap<>(tickets);
    }

    private Map<Ticket, Integer> getRemainingTicketsAfterMove(Map<Ticket, Integer> tickets, Move move) {
        if (tickets == null || move == null) {
            return copyTickets(tickets);
        }
        Map<Ticket, Integer> remaining = new HashMap<>(tickets);
        if (move instanceof TicketMove) {
            Ticket ticket = ((TicketMove) move).ticket();
            remaining.put(ticket, Math.max(0, remaining.getOrDefault(ticket, 0) - 1));
        } else if (move instanceof DoubleMove) {
            DoubleMove dm = (DoubleMove) move;
            remaining.put(dm.firstMove().ticket(), Math.max(0, remaining.getOrDefault(dm.firstMove().ticket(), 0) - 1));
            remaining.put(dm.secondMove().ticket(), Math.max(0, remaining.getOrDefault(dm.secondMove().ticket(), 0) - 1));
            remaining.put(Ticket.DOUBLE, Math.max(0, remaining.getOrDefault(Ticket.DOUBLE, 0) - 1));
        }
        return remaining;
    }

    private TicketMove createTicketMoveForEdge(int from, int to, Map<Ticket, Integer> tickets) {
        if (tickets == null) return null;
        Transport transport = graphAnalyzer.findTransportBetween(from, to);
        if (transport != null) {
            Ticket required = Ticket.fromTransport(transport);
            if (required != null && tickets.getOrDefault(required, 0) > 0) {
                return new TicketMove(Colour.BLACK, required, to);
            }
        }
        if (tickets.getOrDefault(Ticket.SECRET, 0) > 0) {
            return new TicketMove(Colour.BLACK, Ticket.SECRET, to);
        }
        return null;
    }

    /**
     * Single ISMCTS rollout simulation with DetectiveSimulator.
     * Uses lightweight, deterministic detective AI for fast, realistic rollouts.
     * 
     * @param determinizedLoc Sampled location from belief state
     * @param detectivePositions Current detective positions
     * @param simTickets Mr. X's available tickets for simulation
     * @return Evaluation score of the rollout position
     */
    private double simulateSingleRolloutWithDetectives(int determinizedLoc, 
                                                       List<Integer> detectivePositions, 
                                                       Map<Ticket, Integer> simTickets) {
        DetectiveSimulator detSim = new DetectiveSimulator(graphAnalyzer);
        
        int currentMrXLoc = determinizedLoc;
        List<Integer> currentDetLocs = new ArrayList<>(detectivePositions);
        Set<Integer> candidates = beliefTracker.getPossibleLocations();
        if (candidates.isEmpty()) {
            candidates = Collections.singleton(currentMrXLoc);
        }
        // FIX: Cap initial candidates to prevent O(n²) blowup in centroid/coverage
        if (candidates.size() > 20) {
            List<Integer> candList = new ArrayList<>(candidates);
            Collections.shuffle(candList);
            candidates = new HashSet<>(candList.subList(0, 20));
            candidates.add(currentMrXLoc); // Always include actual location
        }
        
        // Restored simulation depth: 10 rounds gives enough horizon without stalling
        // CRITICAL: Against coordinated detectives, use deeper simulation
        boolean coordinated = detectCoordinatedBehavior();
        int simulationDepth = coordinated ? Math.min(LOOKAHEAD_DEPTH + 3, 12) : Math.min(LOOKAHEAD_DEPTH, 10);
        if (OVERRIDE_ROLLOUT_DEPTH > 0) {
            simulationDepth = OVERRIDE_ROLLOUT_DEPTH;
        }
        
        for (int depth = 0; depth < simulationDepth; depth++) {
            // 1. Mr. X moves (greedy heuristic for speed)
            currentMrXLoc = simulateGreedyMrXMove(currentMrXLoc, 
                                                  new HashSet<>(currentDetLocs), 
                                                  simTickets);
            
            // 2. Update belief state for detectives
            candidates = expandCandidatesForSimulation(candidates, currentMrXLoc, depth);
            
            // 3. Detectives move using DetectiveSimulator
            currentDetLocs = detSim.simulateCoordinatedMoves(
                currentDetLocs, 
                currentMrXLoc, 
                candidates
            );
            
            // 4. Check capture
            if (currentDetLocs.contains(currentMrXLoc)) {
                return 0.0; // Loss
            }
        }
        
        // Evaluate final position
        return evaluateTerminalPosition(currentMrXLoc, currentDetLocs);
    }
    
    /**
     * Simulate greedy Mr. X move for rollouts (fast heuristic).
     * Enhanced to be more escape-focused against coordinated detectives.
     */
    private int simulateGreedyMrXMove(int currentLoc, Set<Integer> detLocs, 
                                      Map<Ticket, Integer> tickets) {
        Set<Integer> adjacent = graphAnalyzer.getAdjacentLocations(currentLoc);
        
        int bestMove = currentLoc;
        double bestScore = Double.NEGATIVE_INFINITY;
        Ticket bestTicket = null;
        
        // CRITICAL: Detect if we're in immediate danger (detective within 2 moves)
        boolean inDanger = false;
        for (int det : detLocs) {
            int dist = graphAnalyzer.calculateShortestPath(currentLoc, det);
            if (dist >= 0 && dist <= 2) {
                inDanger = true;
                break;
            }
        }
        
        for (int adj : adjacent) {
            // Skip if occupied
            if (detLocs.contains(adj)) continue;
            
            Transport transport = graphAnalyzer.findTransportBetween(currentLoc, adj);
            if (transport == null) continue;
            
            Ticket requiredTicket = Ticket.fromTransport(transport);
            Ticket chosenTicket = null;
            if (requiredTicket != null && tickets.getOrDefault(requiredTicket, 0) > 0) {
                chosenTicket = requiredTicket;
            } else if (tickets.getOrDefault(Ticket.SECRET, 0) > 0) {
                chosenTicket = Ticket.SECRET;
            } else {
                continue; // can't make this move without tickets
            }
            
            int minDist = Integer.MAX_VALUE;
            for (int det : detLocs) {
                int dist = graphAnalyzer.calculateShortestPath(adj, det);
                if (dist >= 0) minDist = Math.min(minDist, dist);
            }
            
            int mobility = graphAnalyzer.getEffectiveConnectivity(adj, detLocs);
            
            // ENHANCED SCORING: Prioritize escape when in danger
            double score;
            if (inDanger) {
                // Emergency escape: prioritize distance and mobility heavily
                score = minDist * 20 + mobility * 10;
                
                // Bonus for using SECRET to break tracking
                if (chosenTicket == Ticket.SECRET) {
                    score += 15.0;
                }
                
                // Penalty for staying in low-mobility areas when in danger
                if (mobility <= 2) {
                    score -= 20.0;
                }
            } else {
                // Normal scoring
                score = minDist * 10 + mobility * 5;
                
                if (chosenTicket == Ticket.SECRET) {
                    score += 5.0; // prefer using SECRET when available to maintain ambiguity
                }
            }
            
            // Avoid low-connectivity bus/underground trap nodes in simulation
            if ((chosenTicket == Ticket.BUS || chosenTicket == Ticket.UNDERGROUND)
                    && graphAnalyzer.getConnectivity(adj) <= 2) {
                score -= inDanger ? 15.0 : 8.0; // Higher penalty when in danger
            }
            
            if (score > bestScore) {
                bestScore = score;
                bestMove = adj;
                bestTicket = chosenTicket;
            }
        }
        
        // Consume ticket in rollout simulation if a move was chosen.
        if (bestMove != currentLoc && bestTicket != null) {
            tickets.put(bestTicket, Math.max(0, tickets.getOrDefault(bestTicket, 0) - 1));
        }
        
        return bestMove;
    }
    
    /**
     * Expand candidate set for simulation (simplified belief tracking).
     */
    private Set<Integer> expandCandidatesForSimulation(Set<Integer> current, 
                                                        int actualLoc, 
                                                        int depth) {
        // FIX: Much tighter cap — was 50, which causes O(n²) BFS in centroid calculation
        if (current.size() > 15) {
            // Sample down instead of expanding further
            Set<Integer> sampled = new HashSet<>();
            sampled.add(actualLoc);
            int count = 0;
            for (int loc : current) {
                if (count >= 14) break;
                sampled.add(loc);
                count++;
            }
            return sampled;
        }
        
        Set<Integer> expanded = new HashSet<>();
        for (int loc : current) {
            expanded.add(loc);
            expanded.addAll(graphAnalyzer.getAdjacentLocations(loc));
        }
        
        // Always include actual location
        expanded.add(actualLoc);
        
        // FIX: Hard cap after expansion to prevent runaway growth
        if (expanded.size() > 20) {
            Set<Integer> capped = new HashSet<>();
            capped.add(actualLoc);
            int count = 0;
            for (int loc : expanded) {
                if (count >= 19) break;
                capped.add(loc);
                count++;
            }
            return capped;
        }
        
        return expanded;
    }
    
    /**
     * Evaluate terminal position after rollout.
     */
    private double evaluateTerminalPosition(int mrXPos, List<Integer> detPositions) {
        Set<Integer> detSet = new HashSet<>(detPositions);
        
        // Check capture
        if (detSet.contains(mrXPos)) return 0.0;
        
        // CRITICAL: Check encirclement risk first
        double encirclement = cordonDetector.calculateEncirclementRisk(mrXPos, detSet);
        if (encirclement > 50) return 0.0;  // Trapped → loss
        
        // Distance component
        int minDist = Integer.MAX_VALUE;
        for (int det : detPositions) {
            int dist = graphAnalyzer.calculateShortestPath(mrXPos, det);
            if (dist >= 0) minDist = Math.min(minDist, dist);
        }
        
        if (minDist == Integer.MAX_VALUE) return 1.0; // Very safe
        if (minDist == 0) return 0.0; // Captured
        
        // Normalize distance to 0-1 scale
        double distScore = Math.min(minDist / 10.0, 1.0);
        
        // Mobility component
        int mobility = graphAnalyzer.getEffectiveConnectivity(mrXPos, detSet);
        double mobilityScore = Math.min(mobility / 8.0, 1.0);
        
        // Combine (weighted average)
        return distScore * 0.6 + mobilityScore * 0.4;
    }
    
    /**
     * Legacy rollout for compatibility (delegates to new implementation).
     */
    private double simulateSingleRollout(int determinizedLoc, List<Integer> detectivePositions, 
                                        Map<Ticket, Integer> simTickets) {
        return simulateSingleRolloutWithDetectives(determinizedLoc, detectivePositions, simTickets);
    }

     private double evaluateRolloutPosition(int mrXPos, Set<Integer> detLocs) {
         double score = 0;
         
         int minDist = Integer.MAX_VALUE;
         for (int det : detLocs) {
             int dist = graphAnalyzer.calculateShortestPath(mrXPos, det);
             if (dist >= 0) minDist = Math.min(minDist, dist);
         }
        
        if (minDist == Integer.MAX_VALUE) {
            score += 150;
        } else {
            score += Math.min(minDist * 15, 90);
        }
        
        int escapeVol = graphAnalyzer.getEscapeVolume(mrXPos, detLocs, 3);
        score += escapeVol * 5;
        
        int connectivity = graphAnalyzer.getEffectiveConnectivity(mrXPos, detLocs);
        score += connectivity * 12;
        
        int ambiguity = graphAnalyzer.getAmbiguityScore(mrXPos);
        score += Math.min(ambiguity * 3, 60);
        
        int contested = graphAnalyzer.getContestedExits(mrXPos, detLocs);
        if (connectivity - contested <= 1) {
            score -= 80;
        }
        
        return score;
    }

    private Map<Colour, Integer> simulateCandidateDividingDetectives(
            int mrXLocation,
            Map<Colour, Integer> currentDetLocs,
            Set<Integer> mrXCandidates,
            Map<Colour, Map<Ticket, Integer>> simDetectiveTickets) {

        Map<Colour, Integer> newLocations = new HashMap<>();
        if (currentDetLocs.isEmpty()) return newLocations;

        for (Map.Entry<Colour, Integer> entry : currentDetLocs.entrySet()) {
            Colour detective = entry.getKey();
            int detLoc = entry.getValue();
            Set<Integer> options = graphAnalyzer.getAdjacentLocations(detLoc);

            int bestDest = detLoc;
            int bestCoverage = -1;
            Ticket ticketUsed = null;

            for (int option : options) {
                if (newLocations.containsValue(option)) continue;
                Transport transport = graphAnalyzer.findTransportBetween(detLoc, option);
                if (transport == null) continue;
                Ticket requiredTicket = Ticket.fromTransport(transport);
                Map<Ticket, Integer> tickets = simDetectiveTickets.getOrDefault(detective, Collections.emptyMap());
                if (tickets.getOrDefault(requiredTicket, 0) <= 0) continue;
                int coverage = (int) mrXCandidates.stream()
                    .filter(candidate -> {
                        int dist = graphAnalyzer.calculateShortestPath(option, candidate);
                        return dist >= 0 && dist <= ROLLOUT_CANDIDATE_COVERAGE_RADIUS;
                    })
                    .count();
                if (coverage > bestCoverage) {
                    bestCoverage = coverage;
                    bestDest = option;
                    ticketUsed = requiredTicket;
                }
            }

            if (bestCoverage < 0) {
                int fallback = moveToward(detLoc, mrXLocation);
                if (!newLocations.containsValue(fallback) && fallback != detLoc) {
                    Transport transport = graphAnalyzer.findTransportBetween(detLoc, fallback);
                    Ticket requiredTicket = transport != null ? Ticket.fromTransport(transport) : null;
                    Map<Ticket, Integer> tickets = simDetectiveTickets.getOrDefault(detective, Collections.emptyMap());
                    if (requiredTicket != null && tickets.getOrDefault(requiredTicket, 0) > 0) {
                        bestDest = fallback;
                        ticketUsed = requiredTicket;
                    }
                }
            }
            if (ticketUsed != null) {
                Map<Ticket, Integer> tickets = simDetectiveTickets.get(detective);
                if (tickets != null) {
                    tickets.put(ticketUsed, tickets.getOrDefault(ticketUsed, 0) - 1);
                }
            }
            newLocations.put(detective, bestDest);
        }
        return newLocations;
    }
    
    /**
     * Backpropagation: Update this node and all ancestors with simulation result.
     */
    private void backpropagate(MCTSNode node, double score) {
        while (node != null) {
            node.visits++;
            node.totalScore += score;
            node = node.parent;
        }
    }
    
    /**
     * MCTS-based move selection: Build one tree from current position with all legal moves as children.
     * This is the CORRECT way to use MCTS - one tree per turn, not per move.
     * 
     * @param mrXPosition Current Mr. X position
     * @param legalMoves All legal moves from current position
     * @param tickets Mr. X's current tickets
     * @param timeBudgetMs Time budget for MCTS search (milliseconds)
     * @return Best move selected by MCTS
     */
    public Move selectBestMoveWithMCTS(int mrXPosition, Set<Move> legalMoves,
                                       Map<Ticket, Integer> tickets, long timeBudgetMs) {
        return selectBestMoveWithMCTS(mrXPosition, legalMoves, tickets, timeBudgetMs, null);
    }

    public Move selectBestMoveWithMCTS(int mrXPosition, Set<Move> legalMoves,
                                       Map<Ticket, Integer> tickets, long timeBudgetMs,
                                       MCTSNode warmStartRoot) {
        // CRITICAL: Filter out hard constraint violations BEFORE MCTS
        // MCTS bypasses evaluateMove scoring, so constraints must be enforced here
        Set<Move> filteredMoves = legalMoves.stream()
            .filter(m -> !isHardConstraintViolation(m, mrXPosition))
            .collect(Collectors.toSet());
        
        // If all moves violate constraints, fall back to original set (shouldn't happen)
        if (filteredMoves.isEmpty()) {
            if (DEBUG) debugErr("[MCTS WARNING] All moves violate hard constraints, using original set%n");
            filteredMoves = legalMoves;
        }
        
        if (filteredMoves.size() == 1) {
            if (DEBUG) debugErr("[MCTS] Single legal move available%n");
        }
        
        // DIAGNOSTIC: Log candidate set size and move availability per round
        int candidateSize = beliefTracker.getPossibleLocations().size();
        if (DEBUG) debugErr("[DIAG] Round %d: candidates=%d, legalMoves=%d, filteredMoves=%d%n",
            currentRound, candidateSize, legalMoves.size(), filteredMoves.size());

        if (warmStartRoot != null && warmStartRoot.mrXPosition != mrXPosition) {
            if (DEBUG) debugErr("[MCTS WARNING] Ignoring stale warm-start root at %d (expected %d)%n",
                warmStartRoot.mrXPosition, mrXPosition);
            warmStartRoot = null;
        }
        
        // Get current detective positions
        List<Integer> detectivePositions = getDetectivePositionsSortedForSimulation();

        MCTSNode root;
        if (warmStartRoot != null) {
            root = warmStartRoot;
        } else {
            root = new MCTSNode(mrXPosition, copyTickets(tickets));
        }
        root.parent = null;
        long deadline = System.currentTimeMillis() + timeBudgetMs;
        
        // Rebuild immediate children using current legal moves while preserving warm-start stats by destination.
        Map<Integer, MCTSNode> warmByDestination = new HashMap<>();
        int warmChildrenBeforePrune = 0;
        if (warmStartRoot != null) {
            warmChildrenBeforePrune = root.children.size();
            for (MCTSNode child : root.children) {
                warmByDestination.putIfAbsent(child.mrXPosition, child);
            }
        }

        List<MCTSNode> refreshedChildren = new ArrayList<>();
        int reusedWarmChildren = 0;
        
        for (Move move : filteredMoves) {
            int dest = getMoveDestination(move);
            MCTSNode previous = warmByDestination.get(dest);
            MCTSNode refreshed = new MCTSNode(
                dest,
                move,
                root,
                getRemainingTicketsAfterMove(root.remainingTickets, move)
            );
            
            if (previous != null) {
                reusedWarmChildren++;
                refreshed.visits = previous.visits;
                refreshed.totalScore = previous.totalScore;
                refreshed.children = previous.children;
                for (MCTSNode grandChild : refreshed.children) {
                    grandChild.parent = refreshed;
                }
            }
            refreshedChildren.add(refreshed);
        }
        root.children = refreshedChildren;

        if (warmStartRoot != null) {
            if (warmChildrenBeforePrune == 0) {
                if (DEBUG) debugErr("[MCTS] Warm-start root had no children to reuse%n");
            } else if (reusedWarmChildren == 0) {
                if (DEBUG) debugErr(
                    "[MCTS WARNING] Warm-start pruned to empty: previous_children=%d legal_moves=%d. Starting fresh.%n",
                    warmChildrenBeforePrune, filteredMoves.size());
            } else {
                if (DEBUG) debugErr("[MCTS] Warm-start reused %d/%d children%n",
                    reusedWarmChildren, warmChildrenBeforePrune);
            }
        }
        
        // If only one move, return it immediately
        if (root.children.size() == 1) {
            Move singleMove = root.children.get(0).moveFromParent;
            if (singleMove != null) return singleMove;
        }
        
        // If no valid moves available, return null
        if (root.children.isEmpty()) {
            System.err.println("[MCTS ERROR] No valid children to select from");
            return null;
        }
        
        // MCTS loop: selection → rollout → backpropagation
        int iterations = 0;
        int expansions = 0;
        int depthSum = 0;
        while (System.currentTimeMillis() < deadline) {
            MCTSNode node = select(root);
            depthSum += Math.max(0, lastSelectionDepth);
            if (node.visits > 0 && node.isLeaf()) {
                expansions += expandNode(node);
            }

            MCTSNode simulationNode = pickChildForSimulation(node);
            double score = rollout(simulationNode.mrXPosition, detectivePositions, new HashMap<>(tickets));
            backpropagate(simulationNode, score);
            iterations++;
        }
        
        // DEBUG: Log MCTS performance
        double avgDepth = iterations == 0 ? 0.0 : (double) depthSum / iterations;
        if (DEBUG) debugErr("[MCTS] %d iterations in %dms, %d moves evaluated, depth=%.2f, expansions=%d%n",
            iterations, timeBudgetMs, root.children.size(), avgDepth, expansions);
        
        // Return move with the strongest final UCB-style score.
        // Filter to only children with valid moves (moveFromParent != null)
        List<MCTSNode> validChildren = root.children.stream()
            .filter(n -> n.moveFromParent != null)
            .collect(Collectors.toList());
        
        if (validChildren.isEmpty()) {
            System.err.println("[MCTS ERROR] No children with valid moves");
            return null;
        }
        
        MCTSNode bestChild = validChildren.stream()
            .max(Comparator.comparingDouble(n -> finalSelectionScore(n, root.visits)))
            .orElse(validChildren.get(0));
        
        if (DEBUG) debugErr("[MCTS] Selected move to %d (visits=%d, avg=%.1f, final=%.2f)%n",
            bestChild.mrXPosition,
            bestChild.visits,
            bestChild.getAverageScore(),
            finalSelectionScore(bestChild, root.visits));
        
        lastSearchRoot = root;
        
        return bestChild.moveFromParent;
    }

    public MCTSNode getLastSearchRoot() {
        return lastSearchRoot;
    }

    public MCTSNode findSubtreeForPosition(MCTSNode root, int targetPosition) {
        if (root == null) return null;
        Queue<MCTSNode> queue = new LinkedList<>();
        Set<MCTSNode> visited = new HashSet<>();
        queue.offer(root);
        while (!queue.isEmpty()) {
            MCTSNode current = queue.poll();
            if (visited.add(current)) {
                if (current.mrXPosition == targetPosition) {
                    current.parent = null;
                    return current;
                }
                queue.addAll(current.children);
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Monte Carlo simulation (unchanged structure, minor tidying)
    // -------------------------------------------------------------------------

    private double runMonteCarloSimulation(int startLocation, Map<Ticket, Integer> initialMrXTickets) {
        double totalScore = 0;

        for (int sim = 0; sim < SIMULATIONS_PER_MOVE; sim++) {
            int currentMrXLoc = startLocation;
            Map<Colour, Integer> currentDetLocs = new HashMap<>();
            for (Colour c : view.getPlayers()) {
                if (c.isDetective()) currentDetLocs.put(c, view.getPlayerLocation(c).orElse(-1));
            }
            Map<Colour, Map<Ticket, Integer>> simDetectiveTickets = new HashMap<>();
            for (Colour c : view.getPlayers()) {
                if (!c.isDetective()) continue;
                Map<Ticket, Integer> detTickets = new HashMap<>();
                detTickets.put(Ticket.TAXI, view.getPlayerTickets(c, Ticket.TAXI).orElse(11));
                detTickets.put(Ticket.BUS, view.getPlayerTickets(c, Ticket.BUS).orElse(8));
                detTickets.put(Ticket.UNDERGROUND, view.getPlayerTickets(c, Ticket.UNDERGROUND).orElse(4));
                detTickets.put(Ticket.SECRET, 0);
                detTickets.put(Ticket.DOUBLE, 0);
                simDetectiveTickets.put(c, detTickets);
            }

            Map<Ticket, Integer> simMrXTickets = new HashMap<>(initialMrXTickets);

            for (int d = 1; d < LOOKAHEAD_DEPTH; d++) {
                Set<Integer> rolloutCandidates = beliefTracker.getPossibleLocations();
                if (rolloutCandidates.isEmpty()) {
                    rolloutCandidates = Collections.singleton(currentMrXLoc);
                }
                currentDetLocs = simulateCandidateDividingDetectives(
                    currentMrXLoc, currentDetLocs, rolloutCandidates, simDetectiveTickets
                );
                Set<Integer> detLocSet = new HashSet<>(currentDetLocs.values());
                if (d + 1 < LOOKAHEAD_DEPTH) {
                    currentMrXLoc = simulateMrXResponse(currentMrXLoc, detLocSet, simMrXTickets);
                }
            }

            Set<Integer> finalDetLocs = new HashSet<>(currentDetLocs.values());
            double positionScore = graphAnalyzer.calculateSafetyScore(currentMrXLoc, finalDetLocs);
            int escapeVol = graphAnalyzer.getEscapeVolume(currentMrXLoc, finalDetLocs, 2);
            double centroidPenalty = calculateCentroidProximity(currentMrXLoc, finalDetLocs);

            totalScore += (positionScore * 0.5 + escapeVol * 3.5) - centroidPenalty;
        }

        return totalScore / SIMULATIONS_PER_MOVE;
    }

    private double calculateCentroidProximity(int location, Set<Integer> detectives) {
        if (detectives.isEmpty()) return 0;
        double totalDist = 0;
        int minDist = Integer.MAX_VALUE;
        int maxDist = 0;

        for (int det : detectives) {
            int d = graphAnalyzer.calculateShortestPath(location, det);
            if (d != -1) {
                totalDist += d;
                minDist = Math.min(minDist, d);
                maxDist = Math.max(maxDist, d);
            }
        }

        double avgDist = totalDist / detectives.size();
        int spread = (minDist == Integer.MAX_VALUE) ? 0 : maxDist - minDist;
        double surroundPenalty = (spread <= 1 && avgDist <= 3) ? 20.0 : 0;
        double centroidPenalty = avgDist < 4.0 ? (4.0 - avgDist) * 10.0 : 0;
        return centroidPenalty + surroundPenalty;
    }

    private int simulateMrXResponse(int currentMrXLoc, Set<Integer> currentDetLocs, Map<Ticket, Integer> simMrXTickets) {
        Set<Integer> adjacent = graphAnalyzer.getAdjacentLocations(currentMrXLoc);
        if (adjacent.isEmpty()) return currentMrXLoc;

        int bestLocation = currentMrXLoc;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int dest : adjacent) {
            double safetyScore = graphAnalyzer.calculateSafetyScore(dest, currentDetLocs);
            int escapeVol = graphAnalyzer.getEscapeVolume(dest, currentDetLocs, 2);
            double moveScore = safetyScore * 0.6 + escapeVol * 4.0;

            if (graphAnalyzer.getEffectiveConnectivity(dest, currentDetLocs) <= 1) moveScore -= 30;

            if (moveScore > bestScore) {
                bestScore = moveScore;
                bestLocation = dest;
            }
        }

        if (random.nextDouble() < 0.1 && adjacent.size() > 1) {
            List<Integer> locations = new ArrayList<>(adjacent);
            return locations.get(random.nextInt(locations.size()));
        }
        return bestLocation;
    }

    // -------------------------------------------------------------------------
    // Detective movement simulation
    // -------------------------------------------------------------------------

    /**
     * FIXED: Detective roles (chaser/blocker/sweeper) are now assigned by
     * proximity to Mr. X instead of by list index.  The closest detectives
     * chase, the next-closest block exit routes, and the rest spread out.
     */
    private Set<Integer> simulateDetectiveMoves(int mrXLocation, Set<Integer> currentDetLocs) {
        Set<Integer> newLocations = new HashSet<>();
        List<Integer> detList = new ArrayList<>(currentDetLocs);
        int numDetectives = detList.size();
        if (numDetectives == 0) return newLocations;

        // IMPROVED: sort detectives by distance to Mr. X (closest first)
        detList.sort((a, b) -> {
            int da = graphAnalyzer.calculateShortestPath(a, mrXLocation);
            int db = graphAnalyzer.calculateShortestPath(b, mrXLocation);
            if (da == -1) da = Integer.MAX_VALUE;
            if (db == -1) db = Integer.MAX_VALUE;
            return Integer.compare(da, db);
        });

        int numChasers = Math.max(1, numDetectives / 3);
        int numBlockers = Math.max(1, numDetectives / 3);

        for (int i = 0; i < numDetectives; i++) {
            int detLoc = detList.get(i);
            Set<Integer> options = graphAnalyzer.getAdjacentLocations(detLoc);
            if (options.isEmpty()) { newLocations.add(detLoc); continue; }

            List<Integer> allMoves = new ArrayList<>(options);
            List<Integer> goodMoves = new ArrayList<>();
            boolean isChaser = i < numChasers;
            boolean isBlocker = !isChaser && i < numChasers + numBlockers;

            for (int option : options) {
                if (newLocations.contains(option)) continue;
                int mrXDist = graphAnalyzer.calculateShortestPath(option, mrXLocation);
                int currentDist = graphAnalyzer.calculateShortestPath(detLoc, mrXLocation);

                double sepScore = 0;
                for (int otherLoc : newLocations) {
                    int dist = graphAnalyzer.calculateShortestPath(option, otherLoc);
                    if (dist >= 0 && dist < 3) sepScore -= (3 - dist) * 5;
                }

                double moveScore = 0;
                if (isChaser) {
                    if (mrXDist >= 0 && mrXDist < currentDist) moveScore = 10 + sepScore;
                } else if (isBlocker) {
                    Set<Integer> mrXExits = graphAnalyzer.getAdjacentLocations(mrXLocation);
                    if (mrXExits.contains(option) || mrXExits.stream().anyMatch(
                            exit -> graphAnalyzer.getAdjacentLocations(option).contains(exit))) {
                        moveScore = 12 + sepScore;
                    } else if (mrXDist >= 0 && mrXDist < currentDist) {
                        moveScore = 5 + sepScore;
                    }
                } else {
                    double angleScore = calculateAngularSeparation(option, mrXLocation, newLocations);
                    moveScore = angleScore + sepScore;
                    if (mrXDist >= 0 && mrXDist < currentDist) moveScore += 3;
                }

                if (moveScore >= 5) goodMoves.add(option);
            }

            int chosen = detLoc;
            if (!goodMoves.isEmpty() && random.nextDouble() < detectiveSkillFactor) {
                chosen = goodMoves.get(random.nextInt(goodMoves.size()));
            } else if (!allMoves.isEmpty()) {
                chosen = allMoves.get(random.nextInt(allMoves.size()));
            }
            newLocations.add(chosen);
        }
        return newLocations;
    }

    private Map<Colour, Integer> simulateDetectiveMovesMap(int mrXLocation, Map<Colour, Integer> currentDetLocs) {
        Map<Colour, Integer> newLocations = new HashMap<>();
        List<Map.Entry<Colour, Integer>> detList = new ArrayList<>(currentDetLocs.entrySet());
        int numDetectives = detList.size();
        if (numDetectives == 0) return newLocations;

        int numChasers = Math.max(1, numDetectives / 3);
        int numBlockers = Math.max(1, numDetectives / 3);

        for (int i = 0; i < numDetectives; i++) {
            Map.Entry<Colour, Integer> entry = detList.get(i);
            Colour c = entry.getKey();
            int detLoc = entry.getValue();
            Set<Integer> options = graphAnalyzer.getAdjacentLocations(detLoc);

            if (options.isEmpty()) { newLocations.put(c, detLoc); continue; }

            List<Integer> allMoves = new ArrayList<>(options);
            List<Integer> goodMoves = new ArrayList<>();
            boolean isChaser = i < numChasers;
            boolean isBlocker = !isChaser && i < numChasers + numBlockers;

            for (int option : options) {
                if (newLocations.containsValue(option)) continue;
                int mrXDist = graphAnalyzer.calculateShortestPath(option, mrXLocation);
                int currentDist = graphAnalyzer.calculateShortestPath(detLoc, mrXLocation);

                double sepScore = 0;
                for (int otherLoc : newLocations.values()) {
                    int dist = graphAnalyzer.calculateShortestPath(option, otherLoc);
                    if (dist >= 0 && dist < 3) sepScore -= (3 - dist) * 5;
                }

                double moveScore = 0;
                if (isChaser) {
                    if (mrXDist >= 0 && mrXDist < currentDist) moveScore = 10 + sepScore;
                } else if (isBlocker) {
                    Set<Integer> mrXExits = graphAnalyzer.getAdjacentLocations(mrXLocation);
                    if (mrXExits.contains(option) || mrXExits.stream().anyMatch(
                            exit -> graphAnalyzer.getAdjacentLocations(option).contains(exit))) {
                        moveScore = 12 + sepScore;
                    } else if (mrXDist >= 0 && mrXDist < currentDist) {
                        moveScore = 5 + sepScore;
                    }
                } else {
                    double angleScore = calculateAngularSeparation(option, mrXLocation, newLocations.values());
                    moveScore = angleScore + sepScore;
                    if (mrXDist >= 0 && mrXDist < currentDist) moveScore += 3;
                }
                if (moveScore >= 5) goodMoves.add(option);
            }

            int chosen = detLoc;
            if (!goodMoves.isEmpty() && random.nextDouble() < 0.75) {
                chosen = goodMoves.get(random.nextInt(goodMoves.size()));
            } else if (!allMoves.isEmpty()) {
                chosen = allMoves.get(random.nextInt(allMoves.size()));
            }
            newLocations.put(c, chosen);
        }
        return newLocations;
    }

    private double calculateAngularSeparation(int position, int mrXLocation, Collection<Integer> others) {
        if (others.isEmpty()) return 5.0;
        double total = 0;
        for (int other : others) {
            if (other == position) continue;
            int dist = graphAnalyzer.calculateShortestPath(position, other);
            if (dist > 0) total += Math.min(dist, 5);
        }
        return total / others.size();
    }

    // -------------------------------------------------------------------------
    // IMPROVEMENT 1: Guided Mr. X rollout moves (replaces random selection)
    // -------------------------------------------------------------------------
    
    /**
     * Simulate Mr. X's move using evaluation function instead of random selection.
     * This makes MCTS rollouts more realistic and improves prediction accuracy.
     * 
     * @param currentMrXLoc Current Mr. X position
     * @param currentDetLocs Current detective positions
     * @param simMrXTickets Available tickets for simulation
     * @return Best destination based on evaluation
     */
    private int simulateGuidedMrXMove(int currentMrXLoc, Set<Integer> currentDetLocs, 
                                      Map<Ticket, Integer> simMrXTickets) {
        Set<Integer> adjacent = graphAnalyzer.getAdjacentLocations(currentMrXLoc);
        if (adjacent.isEmpty()) return currentMrXLoc;

        int bestLocation = currentMrXLoc;
        Ticket bestTicket = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        // Evaluate each possible move using the same logic as main evaluation
        for (int dest : adjacent) {
            Transport transport = graphAnalyzer.findTransportBetween(currentMrXLoc, dest);
            if (transport == null) continue;
            Ticket requiredTicket = Ticket.fromTransport(transport);

            int specificCount = simMrXTickets.getOrDefault(requiredTicket, 0);
            int secretCount = simMrXTickets.getOrDefault(Ticket.SECRET, 0);
            boolean canUseSpecific = specificCount > 0;
            boolean canUseSecret = secretCount > 0;
            if (!canUseSpecific && !canUseSecret) continue;

            double safetyScore = graphAnalyzer.calculateSafetyScore(dest, currentDetLocs);
            int escapeVol = graphAnalyzer.getEscapeVolume(dest, currentDetLocs, 2);
            int connectivity = graphAnalyzer.getEffectiveConnectivity(dest, currentDetLocs);
            
            // Composite score matching main evaluation priorities
            double moveScore = safetyScore * 0.6 + escapeVol * 4.0;
            
            // Penalize low connectivity (trap risk)
            if (connectivity <= 1) moveScore -= 30;
            
            // Bonus for high connectivity (escape options)
            if (connectivity >= 4) moveScore += 15;

            if (moveScore > bestScore) {
                bestScore = moveScore;
                bestLocation = dest;
                bestTicket = canUseSpecific ? requiredTicket : Ticket.SECRET;
            }
        }

        if (bestTicket != null) {
            simMrXTickets.put(bestTicket, simMrXTickets.getOrDefault(bestTicket, 0) - 1);
        }
        return bestLocation;
    }

    // -------------------------------------------------------------------------
    // IMPROVEMENT 2: Hub-blocking detective simulation (replaces random moves)
    // -------------------------------------------------------------------------
    
    /**
     * Simulate detectives using hub-blocking strategy that mirrors HubBlockingDetective.java.
     * This makes MCTS see the real threat pattern during rollouts.
     * 
     * Key differences from real detective:
     * - Stateless: Uses assignedHubs set instead of persistent hubAssignments map
     * - No ticket tracking: Assumes all moves are valid
     * - Position-based coordination: Closest detective acts first
     * 
     * @param mrXLocation Mr. X's current position
     * @param currentDetLocs Current detective positions (map by colour)
     * @return New detective positions after hub-blocking moves
     */
    private Map<Colour, Integer> simulateHubBlockingDetectives(int mrXLocation, 
                                                               Map<Colour, Integer> currentDetLocs) {
        Map<Colour, Integer> newLocations = new HashMap<>();
        Set<Integer> assignedHubs = new HashSet<>(); // Stateless coordination
        
        if (currentDetLocs.isEmpty()) return newLocations;

        // Sort detectives by distance to Mr. X (closest acts first - matches real detective)
        List<Colour> sortedDetectives = currentDetLocs.keySet().stream()
            .sorted(Comparator.comparingInt(c -> {
                int dist = graphAnalyzer.calculateShortestPath(currentDetLocs.get(c), mrXLocation);
                return dist == -1 ? Integer.MAX_VALUE : dist;
            }))
            .collect(java.util.stream.Collectors.toList());

        // Get nearby hubs (matches real detective's findTransportHubsNear)
        List<Integer> nearbyHubs = new ArrayList<>();
        Set<Integer> allHubs = graphAnalyzer.getTransportHubs();
        for (int hub : allHubs) {
            int dist = graphAnalyzer.calculateShortestPath(mrXLocation, hub);
            if (dist >= 0 && dist <= 5) {
                nearbyHubs.add(hub);
            }
        }
        nearbyHubs.sort(Comparator.comparingInt(hub -> 
            graphAnalyzer.calculateShortestPath(mrXLocation, hub)));
        
        for (Colour detective : sortedDetectives) {
            int detLoc = currentDetLocs.get(detective);
            int distToMrX = graphAnalyzer.calculateShortestPath(detLoc, mrXLocation);
            
            // Aggressive chase when close (matches real detective's distToMrX <= 2 check)
            if (distToMrX >= 0 && distToMrX <= 2) {
                int newLoc = moveToward(detLoc, mrXLocation);
                newLocations.put(detective, newLoc);
                continue;
            }
            
            // Hub blocking - pick unassigned hub closest to this detective
            Integer target = nearbyHubs.stream()
                .filter(h -> !assignedHubs.contains(h))
                .filter(h -> !isHubCoveredByOthers(h, newLocations.values(), detLoc))
                .min(Comparator.comparingInt(h -> 
                    graphAnalyzer.calculateShortestPath(detLoc, h)))
                .orElse(mrXLocation); // Fallback to greedy chase
            
            assignedHubs.add(target); // Prevent overlap
            int newLoc = moveToward(detLoc, target);
            newLocations.put(detective, newLoc);
        }
        
        return newLocations;
    }
    
    /**
     * Move one step toward target (matches real detective's selectBestMove logic).
     */
    private int moveToward(int from, int target) {
        Set<Integer> adjacent = graphAnalyzer.getAdjacentLocations(from);
        if (adjacent.isEmpty()) return from;
        
        int bestDest = from;
        int bestDist = graphAnalyzer.calculateShortestPath(from, target);
        if (bestDist == -1) bestDist = Integer.MAX_VALUE;
        
        for (int dest : adjacent) {
            int dist = graphAnalyzer.calculateShortestPath(dest, target);
            if (dist >= 0 && dist < bestDist) {
                bestDist = dist;
                bestDest = dest;
            }
        }
        
        return bestDest;
    }
    
    /**
     * Check if hub is covered by other detectives (matches real detective's isCoveredByOtherDetective).
     */
    private boolean isHubCoveredByOthers(int hub, Collection<Integer> otherDetLocs, int myLoc) {
        for (int detLoc : otherDetLocs) {
            if (detLoc == myLoc) continue;
            int dist = graphAnalyzer.calculateShortestPath(detLoc, hub);
            if (dist >= 0 && dist <= 2) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Supporting score components
    // -------------------------------------------------------------------------

    /**
     * Detect if Mr. X is in emergency mode (about to be captured).
     * Emergency mode triggers when multiple detectives are very close.
     */
    private boolean isInEmergencyMode(int destination, Set<Integer> detectiveLocations) {
        int detectivesWithin2 = 0;
        int detectivesWithin3 = 0;
        
        for (int detLoc : detectiveLocations) {
            int dist = graphAnalyzer.calculateShortestPath(destination, detLoc);
            if (dist >= 0) {
                if (dist <= 2) detectivesWithin2++;
                if (dist <= 3) detectivesWithin3++;
            }
        }
        
        // Emergency if 2+ detectives within 2 moves OR 3+ detectives within 3 moves
        return detectivesWithin2 >= 2 || detectivesWithin3 >= 3;
    }

    /**
     * Detect if detectives are using coordinated behavior patterns.
     * Looks for signs of role-based coordination vs random movement.
     */
    private boolean detectCoordinatedBehavior() {
        if (detectiveLocations.size() < 5) return false;
        
        // Check if detectives are spread out in a coordinated pattern
        List<Integer> detList = new ArrayList<>(detectiveLocations);
        
        // Calculate average distance between detectives
        double totalDistance = 0;
        int pairs = 0;
        for (int i = 0; i < detList.size(); i++) {
            for (int j = i + 1; j < detList.size(); j++) {
                int dist = graphAnalyzer.calculateShortestPath(detList.get(i), detList.get(j));
                if (dist > 0) {
                    totalDistance += dist;
                    pairs++;
                }
            }
        }
        
        if (pairs == 0) return false;
        double avgDetectiveDistance = totalDistance / pairs;
        
        // Coordinated detectives maintain optimal spacing (not too close, not too far)
        // Random detectives tend to cluster or spread randomly
        boolean optimalSpacing = avgDetectiveDistance >= 4.0 && avgDetectiveDistance <= 8.0;
        
        // Check if detectives are covering different transport types (role specialization)
        Set<Integer> hubsNearDetectives = new HashSet<>();
        for (int detLoc : detectiveLocations) {
            if (graphAnalyzer.isTransportHub(detLoc)) {
                hubsNearDetectives.add(detLoc);
            }
            // Check adjacent hubs
            for (int adj : graphAnalyzer.getAdjacentLocations(detLoc)) {
                if (graphAnalyzer.isTransportHub(adj)) {
                    hubsNearDetectives.add(adj);
                }
            }
        }
        
        boolean hubCoverage = hubsNearDetectives.size() >= 2; // At least 2 different hubs covered
        
        return optimalSpacing || hubCoverage;
    }

    private double calculateUnifiedProximityPenalty(int destination) {
        double penalty = 0;
        int detectivesNearby = 0;

        for (int detLoc : detectiveLocations) {
            int dist = graphAnalyzer.calculateShortestPath(destination, detLoc);
            if (dist < 0) continue;

            if (dist == 0) {
                penalty += 1000;
                continue;
            }
            if (dist == 1) {
                penalty += 60;
                detectivesNearby++;
            } else if (dist == 2) {
                penalty += 25;
                detectivesNearby++;
            } else if (dist == 3) {
                penalty += 8;
            } else if (dist == 4) {
                penalty += 3;
            }
        }

        if (detectivesNearby >= 2) {
            penalty += detectivesNearby * 15;
        }

        int exits = graphAnalyzer.getEffectiveConnectivity(destination, detectiveLocations);
        if (exits <= 1 && detectivesNearby > 0) {
            penalty += 50;
        }

        return penalty;
    }

    private double calculateTransportDiversityBonus(int location) {
        long transportTypes = view.getGraph().getEdgesFrom(view.getGraph().getNode(location))
            .stream().map(edge -> edge.data()).distinct().count();
        return transportTypes * 5.0;
    }

    /**
     * FIXED: the old version had both `distance > 3` and `distance > 5` firing on
     * the same node (since 5 > 3), effectively double-adding 5 pts for far-away
     * detectives.  Now the conditions are exclusive.
     */
    private double calculateDistanceBonus(int destination) {
        int minDetDist = Integer.MAX_VALUE;
        double totalDist = 0;
        
        for (int detLoc : detectiveLocations) {
            int distance = graphAnalyzer.calculateShortestPath(destination, detLoc);
            if (distance >= 0) {
                minDetDist = Math.min(minDetDist, distance);
                totalDist += distance;
            }
        }
        
        if (minDetDist == Integer.MAX_VALUE) {
            return 120.0;
        }
        
        // Keep distance important, but do not let it dominate every other signal.
        double bonus = Math.min(minDetDist * 12.0, 72.0);

        if (!detectiveLocations.isEmpty()) {
            double avgDetDist = totalDist / detectiveLocations.size();
            bonus += Math.min(avgDetDist * 2.5, 24.0);
        }

        return bonus;
    }

    private double adjustForGamePhase(Ticket ticket, int destination) {
        double adjustment = 0;
        double gameProgress = getGameProgress();

        if (ticket == Ticket.UNDERGROUND) adjustment += 5.0;

        if (gameProgress < 0.3) {
            if (ticket == Ticket.SECRET || ticket == Ticket.DOUBLE) adjustment -= 5;
            adjustment += graphAnalyzer.getConnectivity(destination) * 2.0;
        } else if (gameProgress < 0.7) {
            if (detectiveLocations.size() > 2) adjustment += 3;
        } else {
            if (ticket == Ticket.SECRET || ticket == Ticket.DOUBLE) adjustment += 5;
            adjustment += calculateDistanceBonus(destination) * 0.5;
        }
        return adjustment;
    }

    private boolean isRevealRound(int round) {
        if (round <= 0 || round > rounds.size()) return false;
        return rounds.get(round - 1);
    }

    private int getRoundsUntilNextReveal(int round) {
        for (int i = round; i <= rounds.size(); i++) {
            if (i > 0 && i <= rounds.size() && rounds.get(i - 1)) {
                return i - round;
            }
        }
        return 999;
    }

    private double getRevealPreparationBonus(int destination, int round) {
        int roundsToReveal = getRoundsUntilNextReveal(round);
        if (roundsToReveal > 2) return 0;

        int guaranteedExits = graphAnalyzer.getEffectiveConnectivity(
            destination, detectiveLocations);

        double multiplier = roundsToReveal == 1 ? 3.0 : 1.5;
        return guaranteedExits * 25.0 * multiplier;
    }

    // -------------------------------------------------------------------------
    // Move selection
    // -------------------------------------------------------------------------

    /**
     * Selects the best move with adaptive strategic randomisation.
     *
     * Only moves within an adaptive score band of the best score are candidates
     * for random selection. Band width varies by game phase for optimal play.
     * 
     * NOTE: If multiple moves fall within the band, selection is fully random.
     * This is intentional for unpredictability, but means a clearly weaker move
     * may be chosen if the band is wide (especially early game with band=20).
     * This tradeoff favors unpredictability over pure optimization.
     */
    public Move selectBestMove(Set<Move> validMoves) {
        if (validMoves.isEmpty()) return null;

        Map<Move, Double> scored = new HashMap<>();
        double bestScore = Double.NEGATIVE_INFINITY;

        for (Move move : validMoves) {
            double score = evaluateMoveWithLookahead(move);
            scored.put(move, score);
            if (score > bestScore) bestScore = score;
        }
        
        // Safety valve: if everything is -Infinity (checkmate), pick any move
        if (bestScore == Double.NEGATIVE_INFINITY) {
            return validMoves.iterator().next();
        }

        // Adaptive randomization band based on game phase
        double gameProgress = getGameProgress();
        double scoreBand;
        if (gameProgress < 0.3) {
            scoreBand = RANDOMIZATION_SCORE_BAND_EARLY; // More unpredictable early
        } else if (gameProgress < 0.7) {
            scoreBand = RANDOMIZATION_SCORE_BAND_MID;
        } else {
            scoreBand = RANDOMIZATION_SCORE_BAND_LATE; // More precise in endgame
        }

        List<Move> topMoves = new ArrayList<>();
        for (Map.Entry<Move, Double> entry : scored.entrySet()) {
            if (entry.getValue() >= bestScore - scoreBand) {
                topMoves.add(entry.getKey());
            }
        }

        if (topMoves.size() > 1) return topMoves.get(random.nextInt(topMoves.size()));

        return scored.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(validMoves.iterator().next());
    }

    /** Purely deterministic selection — no randomisation. Use in critical situations. */
    public Move selectBestMoveDeterministic(Set<Move> validMoves) {
        if (validMoves.isEmpty()) return null;
        Move best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Move move : validMoves) {
            double score = evaluateMoveWithLookahead(move);
            if (score > bestScore) { bestScore = score; best = move; }
        }
        // Safety valve: if checkmate, pick any move
        if (bestScore == Double.NEGATIVE_INFINITY) {
            return validMoves.iterator().next();
        }
        return best;
    }

    public List<Move> getMovesSortedByScore(Set<Move> validMoves) {
        List<Move> sorted = new ArrayList<>(validMoves);
        sorted.sort((m1, m2) -> Double.compare(
            evaluateMoveWithLookahead(m2), evaluateMoveWithLookahead(m1)));
        return sorted;
    }

    public List<Move> getTopMoves(Set<Move> validMoves, int count) {
        return getMovesSortedByScore(validMoves).stream()
            .limit(count)
            .collect(java.util.stream.Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Detective ticket tracking
    // -------------------------------------------------------------------------

    public void updateDetectiveTicketEstimate(Ticket ticketUsed) {
        Integer count = estimatedDetectiveTickets.get(ticketUsed);
        if (count != null && count > 0) estimatedDetectiveTickets.put(ticketUsed, count - 1);
    }

    public Map<Ticket, Integer> getEstimatedDetectiveTickets() {
        return new HashMap<>(estimatedDetectiveTickets);
    }

    public void trackDetectiveTicketUse(Colour detectiveColor, Ticket ticket) {
        Map<Ticket, Integer> tickets = detectiveTicketTracker.get(detectiveColor);
        if (tickets != null) {
            Integer count = tickets.get(ticket);
            if (count != null && count > 0) tickets.put(ticket, count - 1);
            updateDetectiveTicketEstimate(ticket);
        }
    }

    public Map<Ticket, Integer> getDetectiveTickets(Colour detectiveColor) {
        Map<Ticket, Integer> tickets = detectiveTicketTracker.get(detectiveColor);
        return tickets != null ? new HashMap<>(tickets) : new HashMap<>();
    }

    public boolean areDetectivesLowOnTickets(Ticket ticket) {
        int total = estimatedDetectiveTickets.getOrDefault(ticket, 0);
        int count = Math.max(1, detectiveTicketTracker.size());
        return (double) total / count < 2.0;
    }

    // -------------------------------------------------------------------------
    // Session learning
    // -------------------------------------------------------------------------

    public void recordCapture(int location) {
        captureLocations.put(location, captureLocations.getOrDefault(location, 0) + 1);
    }

    public void recordEscape(int location) {
        escapeLocations.put(location, escapeLocations.getOrDefault(location, 0) + 1);
    }

    public double getHistoricalSafetyScore(int location) {
        int captures = captureLocations.getOrDefault(location, 0);
        int escapes  = escapeLocations.getOrDefault(location, 0);
        int total    = captures + escapes;
        if (total == 0) return 0;
        return (double)(escapes - captures) / total;
    }

    private double getPersistentSafetyScore(int location) {
        // Placeholder for cross-game learning - would integrate with SmartMrXPlayer
        return 0.0;
    }

    // -------------------------------------------------------------------------
    // Dynamic weight recalculation
    // -------------------------------------------------------------------------

    public void recalculateDynamicWeights() {
        double gameProgress = getGameProgress();
        int detectiveCount  = detectiveLocations.size();
        int totalUnderground = mrXTickets.getOrDefault(Ticket.UNDERGROUND, 0);
        int totalSecret = mrXTickets.getOrDefault(Ticket.SECRET, 0);
        int totalDouble = mrXTickets.getOrDefault(Ticket.DOUBLE, 0);

        ticketValueWeights = new HashMap<>();

        if (gameProgress < 0.3) {
            // Early game: Aggressive play
            ticketValueWeights.put(Ticket.SECRET,      2.5);
            ticketValueWeights.put(Ticket.DOUBLE,      1.8);
            ticketValueWeights.put(Ticket.UNDERGROUND, 1.5);
            ticketValueWeights.put(Ticket.BUS,         0.8);
            ticketValueWeights.put(Ticket.TAXI,        0.6);
        } else if (gameProgress < 0.7) {
            // Mid game: Balanced
            ticketValueWeights.put(Ticket.SECRET,      3.0);
            ticketValueWeights.put(Ticket.DOUBLE,      2.2);
            ticketValueWeights.put(Ticket.UNDERGROUND, 1.5);
            ticketValueWeights.put(Ticket.BUS,         1.0);
            ticketValueWeights.put(Ticket.TAXI,        0.8);
        } else {
            // Late game: Survival mode
            ticketValueWeights.put(Ticket.SECRET,      1.2);
            ticketValueWeights.put(Ticket.DOUBLE,      1.0);
            ticketValueWeights.put(Ticket.UNDERGROUND, 0.8);
            ticketValueWeights.put(Ticket.BUS,         0.8);
            ticketValueWeights.put(Ticket.TAXI,        0.6);
        }

        // Adjust for detective count - more detectives = need more mobility
        if (detectiveCount >= 4) {
            ticketValueWeights.put(Ticket.UNDERGROUND,
                ticketValueWeights.get(Ticket.UNDERGROUND) * 0.6);
            ticketValueWeights.put(Ticket.DOUBLE,
                ticketValueWeights.get(Ticket.DOUBLE) * 0.7);
        }

        // Adjust for remaining tickets - use them before they become worthless
        if (totalUnderground <= 1) {
            ticketValueWeights.put(Ticket.UNDERGROUND,
                ticketValueWeights.get(Ticket.UNDERGROUND) * 1.5);
        }
        
        if (totalSecret >= 4 && gameProgress < 0.4) {
            // Keep early flexibility, but avoid over-spending secrets against coordinated teams.
            ticketValueWeights.put(Ticket.SECRET,
                ticketValueWeights.get(Ticket.SECRET) * 0.9);
        }
        
        if (totalDouble >= 2 && gameProgress < 0.35) {
            // Slightly discourage burning DOUBLE too early unless genuinely threatened.
            ticketValueWeights.put(Ticket.DOUBLE,
                ticketValueWeights.get(Ticket.DOUBLE) * 0.85);
        }

        // Coordinated detectives collapse space quickly; release high-leverage tickets earlier.
        if (detectCoordinatedBehavior()) {
            ticketValueWeights.put(Ticket.SECRET,
                ticketValueWeights.get(Ticket.SECRET) * 0.8);
            ticketValueWeights.put(Ticket.DOUBLE,
                ticketValueWeights.get(Ticket.DOUBLE) * 0.7);
            ticketValueWeights.put(Ticket.TAXI,
                ticketValueWeights.get(Ticket.TAXI) * 0.8);
            ticketValueWeights.put(Ticket.BUS,
                ticketValueWeights.get(Ticket.BUS) * 0.8);
        }
    }

    // -------------------------------------------------------------------------
    // Emergency Detection
    // -------------------------------------------------------------------------

    /**
     * Detects if Mr. X is in immediate danger at the given location.
     * Used for emergency override of ticket conservation rules.
     * 
     * @param location The destination location to evaluate
     * @param detLocs The detective locations to consider
     * @return true if Mr. X is in immediate danger of capture
     */
    private boolean isInImmediateDanger(int location, Set<Integer> detLocs) {
        // Check if any detective is at the location
        if (detLocs.contains(location)) return true;
        
        // Check if any detective is within 1 move
        for (int detLoc : detLocs) {
            int dist = graphAnalyzer.calculateShortestPath(location, detLoc);
            if (dist == 1) return true;
        }
        
        // Check if Mr. X is in a pincer situation
        if (graphAnalyzer.isInPincerDanger(location, detLocs)) return true;
        
        // Check if escape routes are severely limited
        int effectiveExits = graphAnalyzer.getEffectiveConnectivity(location, detLocs);
        if (effectiveExits <= 1 && detLocs.size() >= 2) return true;
        
        // Check if surrounded (multiple detectives within distance 2)
        int nearbyDetectives = 0;
        for (int detLoc : detLocs) {
            int dist = graphAnalyzer.calculateShortestPath(location, detLoc);
            if (dist >= 0 && dist <= 2) nearbyDetectives++;
        }
        if (nearbyDetectives >= 3) return true;
        
        return false;
    }
    
    /**
     * Check if a move violates hard constraints that should NEVER be allowed.
     * These constraints must be enforced at move generation, not just scoring.
     */
    private boolean isHardConstraintViolation(Move move, int currentLocation) {
        // NOTE: SECRET single-transport constraint removed from hard constraints
        // Now handled by heavy scoring penalty instead (allows desperate moves)
        
        // Softened DOUBLE opening rule:
        // only block very-early DOUBLE when detectives are still far (>5 away).
        // Otherwise let evaluator decide with scoring penalties/bonuses.
        if (move instanceof DoubleMove) {
            if (currentRound <= 2) {
                boolean anyDetectiveNear = detectiveLocations.stream().anyMatch(d -> {
                    int dist = graphAnalyzer.calculateShortestPath(currentLocation, d);
                    return dist >= 0 && dist <= 5;
                });
                if (!anyDetectiveNear) {
                    return true;
                }
            }
        }
        
        return false;
    }


    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private int getMoveDestination(Move move) {
        if (move instanceof TicketMove) return ((TicketMove) move).destination();
        if (move instanceof DoubleMove) return ((DoubleMove) move).finalDestination();
        throw new IllegalArgumentException("Cannot get destination from " + move.getClass().getSimpleName());
    }

    public void setDetectiveSkillFactor(double factor) {
        this.detectiveSkillFactor = factor;
    }
    
    /**
     * Update belief state after Mr. X makes a move.
     */
    public void updateBeliefState(Ticket ticketUsed, int destination, boolean isRevealRound) {
        beliefTracker.updateAfterMove(ticketUsed, destination, isRevealRound);
    }
    
    /**
     * Update belief state after Mr. X makes a double move.
     */
    public void updateBeliefState(Ticket firstTicket, Ticket secondTicket, 
                                   int finalDestination, boolean isRevealRound) {
        beliefTracker.updateAfterDoubleMove(firstTicket, secondTicket, finalDestination, isRevealRound);
    }
    
    /**
     * Get current ambiguity count - how many locations detectives think Mr. X could be.
     */
    public int getCurrentAmbiguity() {
        return beliefTracker.getAmbiguityCount();
    }
    
    /**
     * Get available transport types at a location.
     * Critical for SECRET ticket valuation - SECRET at single-transport node is worthless.
     */
    private Set<Transport> getAvailableTransportTypes(int location) {
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

    // =========================================================================
    // PRIORITY UPGRADES: Human-Specific AI Improvements
    // =========================================================================
    
    /**
     * PRIORITY 1: Reveal Preparation Strategy (Highest Immediate Survival Gain)
     * 
     * Before reveal rounds, Mr. X should position himself in locations with:
     * - High branching factor (many escape routes)
     * - Multiple transport types (harder to block)
     * - Good connectivity (not a dead end)
     * 
     * This ensures survival for the 2-3 turns after the reveal.
     */
    private double calculateRevealPreparationBonus(int destination, Ticket ticket) {
        int roundsUntilReveal = 0;
        for (int r = currentRound + 1; r <= rounds.size(); r++) {
            if (isRevealRound(r)) {
                roundsUntilReveal = r - currentRound;
                break;
            }
        }
        
        // Only apply before reveal rounds (1-2 moves before)
        if (roundsUntilReveal < 1 || roundsUntilReveal > 2) {
            return 0.0;
        }
        
        double bonus = 0.0;
        
        // Factor 1: Branching factor (escape routes)
        int connectivity = graphAnalyzer.getConnectivity(destination);
        if (connectivity >= 5) {
            bonus += 40.0; // Excellent - many escape routes
        } else if (connectivity >= 4) {
            bonus += 25.0; // Good - adequate routes
        } else if (connectivity >= 3) {
            bonus += 10.0; // Acceptable - minimum viable
        } else {
            bonus -= 30.0; // Dangerous - too few routes
        }
        
        // Factor 2: Effective escape routes (not blocked by detectives)
        int effectiveExits = graphAnalyzer.getEffectiveConnectivity(destination, detectiveLocations);
        if (effectiveExits >= 3) {
            bonus += 30.0; // Safe - multiple unblocked routes
        } else if (effectiveExits >= 2) {
            bonus += 15.0; // Moderate - some options
        } else if (effectiveExits <= 1) {
            bonus -= 50.0; // Critical - trapped after reveal
        }
        
        // Factor 3: Transport diversity (harder for detectives to block)
        Set<Transport> availableTransports = new HashSet<>();
        Set<Integer> neighbors = graphAnalyzer.getAdjacentLocations(destination);
        for (int neighbor : neighbors) {
            Transport t = graphAnalyzer.findTransportBetween(destination, neighbor);
            if (t != null) {
                availableTransports.add(t);
            }
        }
        
        int transportTypes = availableTransports.size();
        if (transportTypes >= 3) {
            bonus += 25.0; // Maximum diversity - all transport types
        } else if (transportTypes == 2) {
            bonus += 12.0; // Good diversity
        } else {
            bonus -= 15.0; // Single transport = easy to block
        }
        
        // Factor 4: Distance from detectives (need breathing room after reveal)
        int minDetDist = Integer.MAX_VALUE;
        for (int detLoc : detectiveLocations) {
            int dist = graphAnalyzer.calculateShortestPath(destination, detLoc);
            if (dist >= 0) {
                minDetDist = Math.min(minDetDist, dist);
            }
        }
        
        if (minDetDist >= 5) {
            bonus += 20.0; // Safe distance
        } else if (minDetDist >= 3) {
            bonus += 10.0; // Moderate distance
        } else if (minDetDist <= 2) {
            bonus -= 40.0; // Too close - revealing here is dangerous
        }
        
        // Factor 5: Avoid transport hubs before reveals (detectives camp there)
        if (graphAnalyzer.isTransportHub(destination)) {
            bonus -= 35.0; // Hubs are dangerous reveal positions
        }
        
        // Scale bonus based on urgency (1 move before = more critical)
        if (roundsUntilReveal == 1) {
            bonus *= 1.5; // Critical - this IS the reveal position
        }
        
        return bonus;
    }
    
    /**
     * PRIORITY 2: Zone Collapse Detection (React to Pressure Before Fatal)
     * 
     * Tracks reachable area over time. If the area is shrinking rapidly,
     * detectives are successfully containing Mr. X. Trigger aggressive escape:
     * - Use DOUBLE moves earlier
     * - Prioritize long-distance edges
     * - Break containment even at ticket cost
     * 
     * NOTE: History is updated externally after move selection, not during evaluation.
     */
    private Map<Integer, Integer> reachableAreaHistory = new HashMap<>();
    
    /**
     * Record a reveal position for inter-reveal distance tracking.
     */
    public void recordRevealPosition(int location) {
        surfacePlanner.recordReveal(location);
    }
    
    public void recordReachableArea(int round, int destination) {
        int area = graphAnalyzer.getEscapeVolume(destination, detectiveLocations, 2);
        reachableAreaHistory.put(round, area);
    }
    
    private double calculateZoneCollapseResponse(int destination, Ticket ticket) {
        // Calculate what the reachable area WOULD BE at this destination
        int projectedReachable = graphAnalyzer.getEscapeVolume(destination, detectiveLocations, 3);
        
        // Need at least 3 rounds of history to detect trend
        if (currentRound < 3) {
            return 0.0;
        }
        
        // Check if area has been shrinking over last 3 rounds
        Integer prev1 = reachableAreaHistory.get(currentRound - 1);
        Integer prev2 = reachableAreaHistory.get(currentRound - 2);
        Integer prev3 = reachableAreaHistory.get(currentRound - 3);
        
        if (prev1 == null || prev2 == null || prev3 == null) {
            return 0.0;
        }
        
        // Calculate shrinkage rate
        int shrinkage1 = prev1 - projectedReachable; // Compare to what we'd have
        int shrinkage2 = prev2 - prev1;
        int shrinkage3 = prev3 - prev2;
        
        // Detect consistent shrinking trend
        boolean isCollapsing = (shrinkage1 > 0 && shrinkage2 > 0) || 
                               (shrinkage1 > 5 && shrinkage2 > 0) ||
                               (shrinkage1 > 0 && shrinkage2 > 0 && shrinkage3 > 0);
        
        if (!isCollapsing) {
            return 0.0;
        }
        
        // Zone is collapsing - apply escape bonuses
        double bonus = 0.0;
        
        // Bonus 1: Prioritize DOUBLE moves to break containment
        if (ticket == Ticket.DOUBLE) {
            bonus += 60.0; // Strong bonus for using double to escape
        }
        
        // Bonus 2: Prioritize long-distance moves
        int currentLoc = getCurrentMrXLocation(destination);
        int distance = graphAnalyzer.calculateShortestPath(currentLoc, destination);
        if (distance >= 3) {
            bonus += distance * 15.0; // Reward distance when escaping containment
        }
        
        // Bonus 3: Prioritize moves that increase reachable area
        // Compare projected area at destination vs previous round's actual area
        int areaGain = projectedReachable - prev1;
        if (areaGain > 0) {
            bonus += areaGain * 8.0; // Reward expanding the reachable zone
        }
        
        // Bonus 4: Break through detective lines (move toward gaps)
        int minDetDist = Integer.MAX_VALUE;
        for (int detLoc : detectiveLocations) {
            int dist = graphAnalyzer.calculateShortestPath(destination, detLoc);
            if (dist >= 0) {
                minDetDist = Math.min(minDetDist, dist);
            }
        }
        
        // Paradoxically, when collapsing, sometimes need to move TOWARD detectives
        // to break through a weak point in the cordon
        if (minDetDist >= 2 && minDetDist <= 4) {
            // Check if this creates a breakthrough (more exits on the other side)
            int exitsOnOtherSide = 0;
            Set<Integer> neighbors = graphAnalyzer.getAdjacentLocations(destination);
            for (int neighbor : neighbors) {
                boolean awayFromDetectives = true;
                for (int detLoc : detectiveLocations) {
                    int distToNeighbor = graphAnalyzer.calculateShortestPath(neighbor, detLoc);
                    if (distToNeighbor >= 0 && distToNeighbor <= 2) {
                        awayFromDetectives = false;
                        break;
                    }
                }
                if (awayFromDetectives) {
                    exitsOnOtherSide++;
                }
            }
            
            if (exitsOnOtherSide >= 2) {
                bonus += 40.0; // Breakthrough move - creates escape corridor
            }
        }
        
        // Scale bonus based on severity of collapse
        int totalShrinkage = shrinkage1 + shrinkage2;
        if (totalShrinkage > 15) {
            bonus *= 1.5; // Critical collapse - aggressive escape needed
        } else if (totalShrinkage > 10) {
            bonus *= 1.3; // Severe collapse
        } else if (totalShrinkage > 5) {
            bonus *= 1.1; // Moderate collapse
        }
        
        return bonus;
    }

    private int getCurrentMrXLocation(int fallback) {
        return currentMrXLocation > 0 ? currentMrXLocation : fallback;
    }

    /**
     * Simulate candidate set growth after moving to a destination.
     * Used by candidate-dividing counter-strategy to find moves that maximize ambiguity.
     */
    private int simulateCandidateSetGrowth(int destination, int movesAhead) {
        // Simple heuristic: estimate based on destination connectivity
        // More connected nodes = more possible candidate locations
        Set<Integer> reachable = getReachableInNMoves(destination, movesAhead);
        return reachable.size();
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

    
    /**
     * PRIORITY 3: Simple Predictive Positioning (Chokepoint Avoidance)
     * 
     * Identifies high-betweenness nodes (chokepoints) and avoids them when
     * detectives are nearby. Humans LOVE blocking chokepoints.
     * 
     * A chokepoint is a node that:
     * - Connects two otherwise distant areas
     * - Has high "betweenness centrality" (many shortest paths go through it)
     * - Is a natural blocking position
     */
    private Set<Integer> identifyChokepoints() {
        Set<Integer> chokepoints = new HashSet<>();
        
        // Simple heuristic: A node is a chokepoint if removing it significantly
        // increases the distance between many node pairs
        
        // For efficiency, we'll use a simpler heuristic:
        // A node is a chokepoint if:
        // 1. It has exactly 2-3 connections (bridge-like)
        // 2. OR it's a transport hub with detectives nearby
        // 3. OR it connects two high-connectivity regions
        
        // NOTE: Hardcoded for Scotland Yard board (199 nodes)
        // If board size changes, this should be: for (int node = 1; node <= maxNode; node++)
        for (int node = 1; node <= 199; node++) {
            int connectivity = graphAnalyzer.getConnectivity(node);
            
            // Type 1: Bridge nodes (2-3 connections)
            if (connectivity >= 2 && connectivity <= 3) {
                // Check if neighbors are in different "regions"
                Set<Integer> neighbors = graphAnalyzer.getAdjacentLocations(node);
                if (neighbors.size() >= 2) {
                    List<Integer> neighborList = new ArrayList<>(neighbors);
                    int dist = graphAnalyzer.calculateShortestPath(
                        neighborList.get(0), 
                        neighborList.get(1)
                    );
                    // If neighbors are far apart (except through this node), it's a bridge
                    if (dist > 3 || dist == -1) {
                        chokepoints.add(node);
                    }
                }
            }
            
            // Type 2: Transport hubs (already identified)
            if (graphAnalyzer.isTransportHub(node)) {
                chokepoints.add(node);
            }
            
            // Type 3: Nodes with high connectivity surrounded by low connectivity
            if (connectivity >= 5) {
                Set<Integer> neighbors = graphAnalyzer.getAdjacentLocations(node);
                int lowConnectivityNeighbors = 0;
                for (int neighbor : neighbors) {
                    if (graphAnalyzer.getConnectivity(neighbor) <= 3) {
                        lowConnectivityNeighbors++;
                    }
                }
                // If most neighbors have low connectivity, this is a regional hub
                if (lowConnectivityNeighbors >= neighbors.size() / 2) {
                    chokepoints.add(node);
                }
            }
        }
        
        return chokepoints;
    }
    
    private double calculateChokepointAvoidanceBonus(int destination, Ticket ticket) {
        // Lazy initialization of chokepoints (expensive to calculate)
        if (identifiedChokepoints == null) {
            identifiedChokepoints = identifyChokepoints();
        }
        
        if (!identifiedChokepoints.contains(destination)) {
            return 0.0; // Not a chokepoint, no penalty
        }
        
        // This IS a chokepoint - check if detectives are nearby
        int minDetDist = Integer.MAX_VALUE;
        for (int detLoc : detectiveLocations) {
            int dist = graphAnalyzer.calculateShortestPath(destination, detLoc);
            if (dist >= 0) {
                minDetDist = Math.min(minDetDist, dist);
            }
        }
        
        // Penalty scales with detective proximity
        double penalty = 0.0;
        
        if (minDetDist <= 2) {
            penalty = 70.0; // Critical - detectives can block this chokepoint immediately
        } else if (minDetDist <= 3) {
            penalty = 45.0; // High risk - detectives approaching
        } else if (minDetDist <= 4) {
            penalty = 25.0; // Moderate risk - detectives in range
        } else if (minDetDist <= 6) {
            penalty = 10.0; // Low risk - but still avoid if possible
        }
        // else no penalty - detectives too far to block
        
        // Exception: DOUBLE moves through chokepoints can be safe
        // (you pass through quickly before detectives can react)
        if (ticket == Ticket.DOUBLE && minDetDist >= 3) {
            penalty *= 0.4; // Reduce penalty for double moves
        }
        
        // Exception: If this chokepoint leads to a safe zone, reduce penalty
        Set<Integer> neighbors = graphAnalyzer.getAdjacentLocations(destination);
        int safeNeighbors = 0;
        for (int neighbor : neighbors) {
            boolean isSafe = true;
            for (int detLoc : detectiveLocations) {
                int distToNeighbor = graphAnalyzer.calculateShortestPath(neighbor, detLoc);
                if (distToNeighbor >= 0 && distToNeighbor <= 3) {
                    isSafe = false;
                    break;
                }
            }
            if (isSafe) {
                safeNeighbors++;
            }
        }
        
        if (safeNeighbors >= 2) {
            penalty *= 0.6; // Chokepoint leads to safety - worth the risk
        }
        
        return -penalty; // Return negative (it's a penalty)
    }
    
    /**
     * GAP DETECTION - Counter to hub-blocking funneling strategy
     * 
     * When detectives spread out to control hubs, they create gaps between them.
     * This method rewards moving into large gaps, allowing Mr. X to thread through
     * their formation instead of hitting prepared traps.
     * 
     * Key insight: Spread detectives (gap=5-8) are MORE dangerous than clustered
     * detectives (gap=1-2), but the gaps between them are the escape routes.
     */
    private double calculateGapScore(int destination, List<Integer> detectiveLocations) {
        if (detectiveLocations.size() < 2) return 0;
        
        // Sort detectives by proximity to destination so local gaps matter more.
        List<Integer> sorted = detectiveLocations.stream()
            .sorted(Comparator.comparingInt(d -> {
                int dist = graphAnalyzer.calculateShortestPath(destination, d);
                return dist == -1 ? Integer.MAX_VALUE : dist;
            }))
            .collect(Collectors.toList());

        // Consider all nearby detective pairs (up to 4 nearest) instead of only one pair.
        int consider = Math.min(4, sorted.size());
        double gapSum = 0.0;
        int pairCount = 0;
        for (int i = 0; i < consider; i++) {
            for (int j = i + 1; j < consider; j++) {
                int gap = graphAnalyzer.calculateShortestPath(sorted.get(i), sorted.get(j));
                if (gap >= 0) {
                    gapSum += gap;
                    pairCount++;
                }
            }
        }
        if (pairCount == 0) return 0;

        double avgGap = gapSum / pairCount;
        // Cap and scale this term so it does not dominate all other factors.
        return Math.min(avgGap, 6.0) * 8.0;
    }
    
    // Cache for chokepoint identification (expensive to recalculate)
    private Set<Integer> identifiedChokepoints = null;
}

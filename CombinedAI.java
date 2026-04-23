package uk.ac.bris.cs.scotlandyard.ai;

import uk.ac.bris.cs.scotlandyard.model.*;
import uk.ac.bris.cs.gamekit.graph.*;
import java.io.File;
import java.util.*;
import java.util.function.Consumer;

import static uk.ac.bris.cs.scotlandyard.ai.ScoringConfig.*;

/**
 * Ultimate Mr X AI - Expert-Level AI with MCTS and Belief State Tracking.
 * 
 * IMPROVEMENTS:
 * - Removed dual search modes (MCTS only)
 * - Integrated with ScoringConfig
 * - Simplified architecture
 * - Better error handling
 * - Opening Book for round 1
 * 
 * SEARCH STRATEGY:
 * - Monte Carlo Tree Search (MCTS) with UCB1 exploration
 * - Belief state tracking for information hiding
 * - Strategic positioning using zones
 * - Surface turn planning for reveal rounds
 * - Opening Book for optimal first moves
 */
public class CombinedAI implements Player, Spectator {

    /**
     * When set to true, disables tuned penalty multipliers and uses baseline values.
     * Used for bug condition testing to get unmasked detective performance.
     */
    public static volatile boolean USE_BASELINE_PARAMETERS = false;
    /** Optional benchmark override for Mr X MCTS move budget (ms). */
    private static final long OVERRIDE_TIME_BUDGET_MS = Long.getLong("bench.mrx.timeBudgetMs", -1L);

    private MoveEvaluator evaluator;
    private GraphAnalyzer graphAnalyzer;
    private BeliefStateTracker beliefTracker;
    private SurfaceTurnPlanner surfacePlanner;
    private boolean initialized = false;
    private static ZoneStrategy sharedZoneStrategy;
    private MCTSNode persistedTree = null;
    
    // PERFORMANCE: Reuse Random instance
    private final Random rng = new Random();
    
    // PERFORMANCE: Cache detective locations
    private Set<Integer> detectiveLocations;
    
    // Move tracking for game analysis
    private static final List<String> moveHistory = new ArrayList<>();
    private static boolean gameStarted = false;
    private static final boolean DEBUG = false;

    private static void debug(String format, Object... args) {
        if (DEBUG) System.out.printf(format, args);
    }
    
    private static void debugErr(String format, Object... args) {
        if (DEBUG) System.err.printf(format, args);
    }

    private static final Comparator<Move> MOVE_COMPARATOR = Comparator.comparing(Move::toString);

    private static List<Move> orderLegalMoves(Set<Move> moves) {
        List<Move> list = new ArrayList<>(moves);
        list.sort(MOVE_COMPARATOR);
        return list;
    }

    @Override
    public void makeMove(ScotlandYardView view, int location, Set<Move> moves, Consumer<Move> callback) {
        // Track game start
        if (!gameStarted) {
            gameStarted = true;
            moveHistory.clear();
            if (DEBUG) System.out.println("[GAME START] New game beginning, move history cleared");
        }
        
        // Build Mr X ticket map
        Map<Ticket, Integer> mrXTickets = new HashMap<>();
        for (Ticket t : Ticket.values()) {
            view.getPlayerTickets(Colour.BLACK, t).ifPresent(count -> mrXTickets.put(t, count));
        }

        if (!initialized) {
            this.graphAnalyzer = new GraphAnalyzer(view.getGraph());
            this.beliefTracker = new BeliefStateTracker(graphAnalyzer, location, mrXTickets);
            this.surfacePlanner = new SurfaceTurnPlanner(graphAnalyzer, view.getRounds());
            synchronized (CombinedAI.class) {
                if (sharedZoneStrategy == null) {
                    sharedZoneStrategy = new ZoneStrategy(graphAnalyzer, 199);
                }
            }
this.evaluator = new MoveEvaluator(graphAnalyzer, beliefTracker, surfacePlanner, sharedZoneStrategy);
            
            // PARAMETER TUNING: 75-game calibrated values
            // Only apply tuned parameters if not in baseline mode (used for bug condition testing)
            if (!USE_BASELINE_PARAMETERS) {
                this.evaluator.setFormationPenaltyMultiplier(3.8);  // 28% → 35% target
                this.evaluator.setCandidateDividingPenaltyMultiplier(5.5);  // 4% → 15-20% target
                if (DEBUG) System.out.println("[TUNING] Formation: 3.8x, Candidate-dividing: 5.5x (75-game calibrated)");
            } else {
                if (DEBUG) System.out.println("[TUNING] Using baseline parameters for bug condition testing");
            }
            
            initialized = true;
        }
        
        // Cache detective locations for this turn
        this.detectiveLocations = getDetectiveLocations(view);
        
        this.evaluator.updateTurnContext(view, detectiveLocations, mrXTickets, location);
        
        long startTime = System.currentTimeMillis();
        int round = view.getCurrentRound();

        Move bestMove;
        
        // Use pure MCTS for move selection
        MCTSNode warmStartRoot = evaluator.findSubtreeForPosition(persistedTree, location);
        long budgetMs = OVERRIDE_TIME_BUDGET_MS > 0 ? OVERRIDE_TIME_BUDGET_MS : TIME_BUDGET_MS;
        bestMove = evaluator.selectBestMoveWithMCTS(
            location, moves, mrXTickets, budgetMs, warmStartRoot
        );
        
        if (bestMove == null && isRevealMoveRound(view, round)) {
            // Reveal-round safety fallback still available if search fails.
            bestMove = selectRevealSafetyMove(moves);
        }
        
        // Fallback if MCTS fails
        if (bestMove == null) {
            System.err.println("[WARNING] MCTS returned null, using fallback");
            bestMove = selectFallbackMove(moves);
        }

        // High-pressure override: when candidate pressure is strong or reveal is imminent,
        // prefer a heuristic move that explicitly maximizes ambiguity and safety.
        if (shouldOverrideMCTSWithHeuristic(view, round, mrXTickets)) {
            Move heuristicMove = selectRevealSafetyMove(moves);
            if (heuristicMove != null && !heuristicMove.equals(bestMove)) {
                double bestScore = evaluator.evaluateMoveWithLookahead(bestMove);
                double heuristicScore = evaluator.evaluateMoveWithLookahead(heuristicMove);
                if (heuristicScore > bestScore + 10.0) {
                    if (DEBUG) System.out.printf("[PRESSURE_OVERRIDE] Using heuristic move over MCTS: %.2f > %.2f%n",
                        heuristicScore, bestScore);
                    bestMove = heuristicMove;
                }
            }
        }
        
        // Optional light randomization among near-best moves (disabled by default vs coordinated
        // detectives — strong opponents already maximize against a distribution; random picks cost Elo).
        bestMove = addUnpredictability(bestMove, moves, location, mrXTickets);
        
        long elapsedTime = System.currentTimeMillis() - startTime;
        
        // Logging
        int roundsUntilReveal = getRoundsUntilNextReveal(view, round);
        int secretCount = mrXTickets.getOrDefault(Ticket.SECRET, 0);
        int doubleCount = mrXTickets.getOrDefault(Ticket.DOUBLE, 0);
        int transportCount = getTransportCount(view, location);
        
        String moveType = getMoveType(bestMove);
        if (DEBUG) {
            System.out.printf("Round %2d: %s [MCTS %dms] | SECRET=%d DOUBLE=%d | reveal_in=%d | transports=%d%n",
                round, moveType, elapsedTime, secretCount, doubleCount, roundsUntilReveal, transportCount);
        }
        
        // Record move tracking data
        int finalDestination = getMoveDestination(bestMove);
        persistedTree = evaluator.findSubtreeForPosition(evaluator.getLastSearchRoot(), finalDestination);
        evaluator.recordReachableArea(round, finalDestination);

        // Diagnostic move logging (active only during DiagnosticRunner)
        MoveLogger logger = MoveLogger.getActive();
        if (logger != null) {
            List<Map.Entry<Move, Double>> top3 = evaluator.getTopMovesWithScores(3);
            final Move finalBestMove = bestMove;
            double chosenScore = top3.stream()
                .filter(e -> e.getKey().equals(finalBestMove))
                .mapToDouble(Map.Entry::getValue)
                .findFirst()
                .orElse(evaluator.evaluateMoveWithLookahead(bestMove));
            int candidateSize = beliefTracker.getPossibleLocations().size();
            logger.recordTurn(round, location, new ArrayList<>(detectiveLocations),
                bestMove, chosenScore, top3, candidateSize);
        }
        
        // Record reveal positions
        boolean revealThisMove = isRevealMoveRound(view, round);
        if (revealThisMove) {
            evaluator.recordRevealPosition(finalDestination);
        }
        
        // Update persistent belief tracking before committing move.
        applyBeliefUpdates(view, round, bestMove);

        // Record move for game analysis
        recordMove(round, bestMove, location, finalDestination, revealThisMove);

        // Check if game is ending
        if (round >= view.getRounds().size() || moves.size() == 0) {
            writeGameResults(round >= view.getRounds().size() ? "MR_X_WIN" : "DETECTIVES_WIN", round);
        }

        callback.accept(bestMove);
    }

    @Override
    public void onMoveMade(ScotlandYardView view, Move move) {
        if (evaluator == null) {
            return;
        }
        if (move.colour().isDetective() && move instanceof TicketMove) {
            TicketMove ticketMove = (TicketMove) move;
            evaluator.trackDetectiveTicketUse(move.colour(), ticketMove.ticket());
        }
    }
    
    /**
     * Fallback move selection when MCTS fails.
     * Uses simple heuristic: pick move to location farthest from detectives.
     */
    private Move selectFallbackMove(Set<Move> moves) {
        if (moves.isEmpty()) return null;
        
        Move bestMove = null;
        int maxMinDistance = -1;
        
        for (Move move : moves) {
            int destination = getMoveDestination(move);
            int minDetectiveDistance = Integer.MAX_VALUE;
            
            for (int detLoc : detectiveLocations) {
                int dist = graphAnalyzer.calculateShortestPath(destination, detLoc);
                if (dist >= 0) {
                    minDetectiveDistance = Math.min(minDetectiveDistance, dist);
                }
            }
            
            if (minDetectiveDistance > maxMinDistance) {
                maxMinDistance = minDetectiveDistance;
                bestMove = move;
            }
        }
        
        return bestMove != null ? bestMove : moves.iterator().next();
    }
    
    /**
     * Record a move for post-game analysis
     */
    private void recordMove(int round, Move move, int fromLocation, int toLocation, boolean isReveal) {
        String moveDesc;
        if (move instanceof DoubleMove) {
            DoubleMove dm = (DoubleMove) move;
            moveDesc = String.format("Round %2d: DOUBLE %s→%d→%d %s", 
                round, 
                isReveal ? "[REVEAL] " : "",
                dm.firstMove().destination(),
                toLocation,
                isReveal ? String.format("(revealed at %d)", toLocation) : "");
        } else if (move instanceof TicketMove) {
            TicketMove tm = (TicketMove) move;
            moveDesc = String.format("Round %2d: %s %s→%d %s", 
                round,
                tm.ticket(),
                isReveal ? "[REVEAL] " : "",
                toLocation,
                isReveal ? String.format("(revealed at %d)", toLocation) : "");
        } else {
            moveDesc = String.format("Round %2d: UNKNOWN %d→%d", round, fromLocation, toLocation);
        }
        
        moveHistory.add(moveDesc);
    }
    
    /**
     * Write game results to file
     */
    private void writeGameResults(String outcome, int finalRound) {
        try {
            java.io.FileWriter writer = new java.io.FileWriter("game_results.md", true);
            writer.write("\n## Game Result: " + outcome + " (Round " + finalRound + ")\n");
            writer.write("Date: " + java.time.LocalDateTime.now() + "\n\n");
            writer.write("### Mr. X Move History:\n");
            for (String move : moveHistory) {
                writer.write(move + "\n");
            }
            writer.write("\n---\n");
            writer.close();
            
            System.out.println("[GAME END] Results written to game_results.md");
            
            // Reset for next game - ensure clean slate for all stateful components
            gameStarted = false;
            moveHistory.clear();
            initialized = false;
            persistedTree = null;
            beliefTracker = null;
            evaluator = null;
            surfacePlanner = null;
            graphAnalyzer = null;
            detectiveLocations = null;
        } catch (java.io.IOException e) {
            System.err.println("[ERROR] Failed to write game results: " + e.getMessage());
        }
    }
    
    
    private int getTransportCount(ScotlandYardView view, int location) {
        Set<Transport> transports = new HashSet<>();
        Node<Integer> node = view.getGraph().getNode(location);
        if (node == null) return 0;
        
        for (Edge<Integer, Transport> edge : view.getGraph().getEdgesFrom(node)) {
            transports.add(edge.data());
        }
        return transports.size();
    }
    
    private int getRoundsUntilNextReveal(ScotlandYardView view, int currentRound) {
        List<Boolean> rounds = view.getRounds();
        for (int i = currentRound; i < rounds.size(); i++) {
            if (rounds.get(i)) return i - currentRound + 1;
        }
        return 999; // No more reveals
    }
    
    private String getMoveType(Move move) {
        if (move instanceof DoubleMove) {
            DoubleMove dm = (DoubleMove) move;
            return String.format("DOUBLE[%s+%s]", 
                dm.firstMove().ticket(), dm.secondMove().ticket());
        } else if (move instanceof TicketMove) {
            TicketMove tm = (TicketMove) move;
            return tm.ticket().toString();
        }
        return "PASS";
    }

    private Set<Integer> getDetectiveLocations(ScotlandYardView view) {
        Set<Integer> locs = new HashSet<>();
        if (view == null) return locs;
        
        for (Colour c : view.getPlayers()) {
            if (c.isDetective()) {
                view.getPlayerLocation(c).ifPresent(locs::add);
            }
        }
        return locs;
    }

    private int getMoveDestination(Move move) {
        if (move instanceof TicketMove) return ((TicketMove) move).destination();
        if (move instanceof DoubleMove) return ((DoubleMove) move).finalDestination();
        return 0;
    }

    private Move selectRevealSafetyMove(Set<Move> moves) {
        if (moves == null || moves.isEmpty()) {
            return null;
        }

        Move bestMove = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (Move move : moves) {
            double score = evaluator.evaluateMoveWithLookahead(move);
            if (score > bestScore) {
                bestScore = score;
                bestMove = move;
            }
        }

        return bestMove != null ? bestMove : moves.iterator().next();
    }

    private boolean shouldOverrideMCTSWithHeuristic(ScotlandYardView view, int round, Map<Ticket, Integer> mrXTickets) {
        Set<Integer> candidates = beliefTracker.getPossibleLocations();
        int candidateSize = candidates.size();
        int roundsUntilReveal = getRoundsUntilNextReveal(view, round);
        int ambiguity = evaluator.getCurrentAmbiguity();

        if (roundsUntilReveal <= 1) return true;
        if (candidateSize <= 12) return true;
        if (ambiguity <= 15) return true;
        if (mrXTickets.getOrDefault(Ticket.SECRET, 0) <= 1 && candidateSize <= 18) return true;
        return false;
    }

    private void applyBeliefUpdates(ScotlandYardView view, int currentRound, Move move) {
        if (beliefTracker == null) {
            return;
        }

        if (move instanceof TicketMove) {
            TicketMove ticketMove = (TicketMove) move;
            boolean currentReveal = isRevealMoveRound(view, currentRound);
            evaluator.updateBeliefState(ticketMove.ticket(), ticketMove.destination(), currentReveal);
            beliefTracker.recordTicketUsage(ticketMove.ticket());
            evaluator.recordMrXTicketUsed(ticketMove.ticket());
            return;
        }
        if (move instanceof DoubleMove) {
            DoubleMove doubleMove = (DoubleMove) move;
            TicketMove first = doubleMove.firstMove();
            TicketMove second = doubleMove.secondMove();

            boolean firstReveal = isRevealMoveRound(view, currentRound);
            boolean secondReveal = isRevealMoveRound(view, currentRound + 1);

            // Process both legs independently because each leg corresponds to a separate round.
            evaluator.updateBeliefState(first.ticket(), first.destination(), firstReveal);
            beliefTracker.recordTicketUsage(first.ticket());
            evaluator.recordMrXTicketUsed(first.ticket());

            evaluator.updateBeliefState(second.ticket(), second.destination(), secondReveal);
            beliefTracker.recordTicketUsage(second.ticket());
            evaluator.recordMrXTicketUsed(second.ticket());

            beliefTracker.recordTicketUsage(Ticket.DOUBLE);
        }
    }

    private boolean isRevealMoveRound(ScotlandYardView view, int roundIndex) {
        return roundIndex >= 0
            && roundIndex < view.getRounds().size()
            && view.getRounds().get(roundIndex);
    }
    
    /**
     * Optional unpredictability: small chance to pick randomly from top 3 heuristic moves.
     * Kept at 0% default for maximum strength vs coordinated AI.
     */
    private static final double UNPREDICTABILITY_TOP3_CHANCE = 0.0;

    private Move addUnpredictability(Move bestMove, Set<Move> moves, int location, Map<Ticket, Integer> tickets) {
        if (bestMove == null || moves.size() < 3) {
            return bestMove;
        }

        if (UNPREDICTABILITY_TOP3_CHANCE > 0 && rng.nextDouble() < UNPREDICTABILITY_TOP3_CHANCE) {
            List<Move> scoredMoves = new ArrayList<>(moves);
            scoredMoves.sort((m1, m2) -> {
                double s1 = evaluator.evaluateMoveWithLookahead(m1);
                double s2 = evaluator.evaluateMoveWithLookahead(m2);
                return Double.compare(s2, s1);
            });

            int topN = Math.min(3, scoredMoves.size());
            int randomIndex = rng.nextInt(topN);
            Move selected = scoredMoves.get(randomIndex);

            if (selected != bestMove && DEBUG) {
                System.out.printf("[UNPREDICTABILITY] Selected move %d of top %d instead of best move%n",
                    randomIndex + 1, topN);
            }
            return selected;
        }

        return bestMove;
    }
}

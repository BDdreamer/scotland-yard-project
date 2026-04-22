package uk.ac.bris.cs.scotlandyard.ai;

import uk.ac.bris.cs.gamekit.graph.Graph;
import uk.ac.bris.cs.scotlandyard.model.Colour;
import uk.ac.bris.cs.scotlandyard.model.DoubleMove;
import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.Player;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYardView;
import uk.ac.bris.cs.scotlandyard.model.Spectator;
import uk.ac.bris.cs.scotlandyard.model.Ticket;
import uk.ac.bris.cs.scotlandyard.model.TicketMove;
import uk.ac.bris.cs.scotlandyard.model.Transport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Candidate-dividing detective AI with sequence-correct belief tracking.
 */
public class CandidateDividingDetective implements Player, Spectator {

    private static final Set<Integer> VALID_MRX_STARTS = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList(
            13, 26, 29, 34, 50, 53, 91, 94, 103, 112,
            117, 132, 138, 141, 155, 174, 197, 198
        )));

    private final Colour colour;
    private final GraphAnalyzer graphAnalyzer;
    private final Tracker tracker;

    public CandidateDividingDetective(GraphAnalyzer graphAnalyzer, Graph<Integer, Transport> graph) {
        this(Colour.RED, graphAnalyzer, new Tracker(graphAnalyzer, graph));
    }

    public CandidateDividingDetective(Colour colour, GraphAnalyzer graphAnalyzer, Tracker tracker) {
        this.colour = colour;
        this.graphAnalyzer = graphAnalyzer;
        this.tracker = tracker;
    }

    CandidateDividingDetective(GraphAnalyzer graphAnalyzer, Tracker tracker) {
        this(Colour.RED, graphAnalyzer, tracker);
    }

    @Override
    public void onMoveMade(ScotlandYardView view, Move move) {
        tracker.onMoveMade(view, move);
    }

    @Override
    public void onRoundStarted(ScotlandYardView view, int round) {
        tracker.onRoundStarted(view, round);
    }

    @Override
    public void onRotationComplete(ScotlandYardView view) {
        tracker.onRotationComplete(view);
    }

    @Override
    public void makeMove(ScotlandYardView view, int location, Set<Move> moves, Consumer<Move> callback) {
        Set<Integer> fullCandidateSet = tracker.snapshotCandidates(view);
        Set<Integer> myPartition = partitionCandidates(fullCandidateSet, view);

        // Fall back to full set if partition is empty (e.g. fewer candidates than detectives)
        Set<Integer> targetSet = myPartition.isEmpty() ? fullCandidateSet : myPartition;

        Move best = moves.stream()
            .filter(TicketMove.class::isInstance)
            .max(Comparator.comparingInt(move -> candidatesCovered(getDestination(move), targetSet)))
            .orElse(moves.iterator().next());

        callback.accept(best);
    }

    /**
     * Partition the candidate set by detective colour so each detective covers a distinct zone.
     * Detectives are sorted alphabetically by colour name for a stable, deterministic assignment.
     * The candidate set is sorted and sliced into equal partitions.
     */
    private Set<Integer> partitionCandidates(Set<Integer> candidates, ScotlandYardView view) {
        List<Colour> detectives = view.getPlayers().stream()
            .filter(Colour::isDetective)
            .sorted(Comparator.comparing(Colour::name))
            .collect(Collectors.toList());

        int myIndex = detectives.indexOf(this.colour);
        if (myIndex < 0 || detectives.isEmpty()) return candidates; // safety fallback

        List<Integer> sorted = new ArrayList<>(candidates);
        Collections.sort(sorted);

        int total = sorted.size();
        int numDetectives = detectives.size();
        int partitionSize = (total + numDetectives - 1) / numDetectives; // ceiling division

        int from = myIndex * partitionSize;
        int to = Math.min(from + partitionSize, total);

        if (from >= total) return Collections.emptySet();
        return new HashSet<>(sorted.subList(from, to));
    }

    int getCandidateCount(ScotlandYardView view) {
        return tracker.snapshotCandidates(view).size();
    }

    private int candidatesCovered(int position, Set<Integer> candidates) {
        return (int) candidates.stream()
            .filter(candidate -> {
                int distance = graphAnalyzer.calculateShortestPath(position, candidate);
                return distance >= 0 && distance <= 2;
            })
            .count();
    }

    private int getDestination(Move move) {
        if (move instanceof TicketMove) {
            return ((TicketMove) move).destination();
        }
        return -1;
    }

    static final class Tracker implements Spectator {

        private final GraphAnalyzer graphAnalyzer;
        private final Graph<Integer, Transport> graph;

        private final List<Ticket> moveHistorySinceLastReveal = new ArrayList<>();
        private Set<Integer> candidateSet;
        private int lastRevealLocation = -1;
        private int pendingMrXTicketMovesToSkip = 0;

        Tracker(GraphAnalyzer graphAnalyzer, Graph<Integer, Transport> graph) {
            this.graphAnalyzer = graphAnalyzer;
            this.graph = graph;
            this.candidateSet = new HashSet<>(VALID_MRX_STARTS);
        }

        synchronized Set<Integer> snapshotCandidates(ScotlandYardView view) {
            pruneDetectiveOccupiedNodes(view);
            if (candidateSet.isEmpty()) {
                resetToFallback(view);
            }
            return new HashSet<>(candidateSet);
        }

        synchronized int getCandidateCount(ScotlandYardView view) {
            pruneDetectiveOccupiedNodes(view);
            if (candidateSet.isEmpty()) {
                resetToFallback(view);
            }
            return candidateSet.size();
        }

        /**
         * Get a copy of the move history since the last reveal.
         * Used by CoordinatedDetectiveAI to infer Mr X's transport mode.
         * 
         * @return list of tickets used by Mr X since last reveal
         */
        synchronized List<Ticket> getMoveHistorySinceLastReveal() {
            return new ArrayList<>(moveHistorySinceLastReveal);
        }

        @Override
        public synchronized void onMoveMade(ScotlandYardView view, Move move) {
            if (move.colour() == Colour.BLACK) {
                handleMrXMove(view, move);
            } else if (move.colour().isDetective()) {
                pruneDetectiveOccupiedNodes(view);
            }
        }

        @Override
        public synchronized void onRoundStarted(ScotlandYardView view, int round) {
            pruneDetectiveOccupiedNodes(view);
        }

        @Override
        public synchronized void onRotationComplete(ScotlandYardView view) {
            pruneDetectiveOccupiedNodes(view);
        }

        private void handleMrXMove(ScotlandYardView view, Move move) {
            if (move instanceof DoubleMove) {
                DoubleMove doubleMove = (DoubleMove) move;
                int round = view.getCurrentRound();

                boolean firstReveal = isRevealRound(view, round);
                boolean secondReveal = isRevealRound(view, round + 1);

                applyObservedMrXLeg(doubleMove.firstMove().ticket(), doubleMove.firstMove().destination(), firstReveal);
                applyObservedMrXLeg(doubleMove.secondMove().ticket(), doubleMove.secondMove().destination(), secondReveal);
                // ScotlandYardModel emits two follow-up TicketMove spectator events for the same double.
                // Skip those to avoid applying the same evidence twice.
                pendingMrXTicketMovesToSkip = 2;

                pruneDetectiveOccupiedNodes(view);
                if (candidateSet.isEmpty()) {
                    resetToFallback(view);
                }
                return;
            }
            if (!(move instanceof TicketMove)) {
                return;
            }
            if (pendingMrXTicketMovesToSkip > 0) {
                pendingMrXTicketMovesToSkip--;
                return;
            }

            TicketMove ticketMove = (TicketMove) move;
            int round = view.getCurrentRound();
            applyObservedMrXLeg(ticketMove.ticket(), ticketMove.destination(), isRevealRound(view, round));

            pruneDetectiveOccupiedNodes(view);
            if (candidateSet.isEmpty()) {
                resetToFallback(view);
            }
        }

        private void applyObservedMrXLeg(Ticket ticket, int destination, boolean isRevealLeg) {
            if (isRevealLeg) {
                candidateSet = new HashSet<>(Collections.singleton(destination));
                lastRevealLocation = destination;
                moveHistorySinceLastReveal.clear();
            } else {
                moveHistorySinceLastReveal.add(ticket);
                recomputeCandidates();
            }
        }

        private void recomputeCandidates() {
            Set<Integer> simulated = baseCandidates();
            for (Ticket ticket : moveHistorySinceLastReveal) {
                simulated = expandCandidatesWithTicket(simulated, ticket);
                if (simulated.isEmpty()) {
                    break;
                }
            }
            candidateSet = simulated;
        }

        private Set<Integer> baseCandidates() {
            if (lastRevealLocation > 0) {
                return new HashSet<>(Collections.singleton(lastRevealLocation));
            }
            return new HashSet<>(VALID_MRX_STARTS);
        }

        private Set<Integer> expandCandidatesWithTicket(Set<Integer> candidates, Ticket ticket) {
            Set<Integer> expanded = new HashSet<>();
            for (int location : candidates) {
                if (!isValidBoardLocation(location)) {
                    continue;
                }
                if (ticket == Ticket.SECRET) {
                    expanded.addAll(graphAnalyzer.getAdjacentLocations(location));
                } else {
                    expanded.addAll(getAdjacentLocationsByTicket(location, ticket));
                }
            }
            expanded.removeIf(location -> !isValidBoardLocation(location));
            return expanded;
        }

        private Set<Integer> getAdjacentLocationsByTicket(int location, Ticket ticket) {
            if (ticket == Ticket.DOUBLE) {
                return Collections.emptySet();
            }

            Set<Integer> adjacent = new HashSet<>();
            for (int destination : graphAnalyzer.getAdjacentLocations(location)) {
                if (ticketMatches(location, destination, ticket)) {
                    adjacent.add(destination);
                }
            }
            return adjacent;
        }

        private boolean ticketMatches(int from, int to, Ticket ticket) {
            Collection<Transport> transports = graphAnalyzer.getAllTransportsBetween(from, to);
            for (Transport transport : transports) {
                if (Ticket.fromTransport(transport) == ticket) {
                    return true;
                }
            }
            return false;
        }

        private void pruneDetectiveOccupiedNodes(ScotlandYardView view) {
            candidateSet.removeAll(getDetectiveLocations(view));
        }

        private Set<Integer> getDetectiveLocations(ScotlandYardView view) {
            Set<Integer> detectiveLocations = new HashSet<>();
            for (Colour colour : view.getPlayers()) {
                if (colour.isDetective()) {
                    view.getPlayerLocation(colour).ifPresent(detectiveLocations::add);
                }
            }
            return detectiveLocations;
        }

        private void resetToFallback(ScotlandYardView view) {
            Set<Integer> fallback = new HashSet<>();
            for (var node : graph.getNodes()) {
                int location = node.value();
                if (isValidBoardLocation(location)) {
                    fallback.add(location);
                }
            }
            fallback.removeAll(getDetectiveLocations(view));
            candidateSet = fallback;
        }

        private boolean isRevealRound(ScotlandYardView view, int round) {
            return round > 0
                && round <= view.getRounds().size()
                && view.getRounds().get(round - 1);
        }

        private boolean isValidBoardLocation(int location) {
            return location > 0 && location <= 199;
        }
    }
}

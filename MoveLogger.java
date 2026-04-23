package uk.ac.bris.cs.scotlandyard.ai;

import uk.ac.bris.cs.scotlandyard.model.Move;

import java.util.*;

/**
 * Collects per-turn diagnostic data for a single game.
 * Attach to CombinedAI via MoveLogger.setActive() before a game starts.
 */
public class MoveLogger {

    public static class TurnRecord {
        public final int round;
        public final int mrXPosition;
        public final List<Integer> detectivePositions;
        public final Move chosenMove;
        public final double chosenScore;
        public final List<Map.Entry<Move, Double>> top3Alternatives; // includes chosen
        public final int candidateSetSize;

        TurnRecord(int round, int mrXPosition, List<Integer> detectivePositions,
                   Move chosenMove, double chosenScore,
                   List<Map.Entry<Move, Double>> top3, int candidateSetSize) {
            this.round = round;
            this.mrXPosition = mrXPosition;
            this.detectivePositions = Collections.unmodifiableList(new ArrayList<>(detectivePositions));
            this.chosenMove = chosenMove;
            this.chosenScore = chosenScore;
            this.top3Alternatives = Collections.unmodifiableList(new ArrayList<>(top3));
            this.candidateSetSize = candidateSetSize;
        }
    }

    public static class GameLog {
        public final int gameIndex;
        public final List<TurnRecord> turns = new ArrayList<>();
        public String outcome = "UNKNOWN"; // "MR_X_WIN" or "DETECTIVE_WIN"
        public int finalRound = -1;

        GameLog(int gameIndex) { this.gameIndex = gameIndex; }

        public void setOutcome(boolean mrXWon, int round) {
            this.outcome = mrXWon ? "MR_X_WIN" : "DETECTIVE_WIN";
            this.finalRound = round;
        }

        public String format() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== GAME %d | %s | rounds=%d ===\n",
                gameIndex, outcome, finalRound));
            for (TurnRecord t : turns) {
                sb.append(String.format(
                    "  R%02d | mrX=%-4d | dets=%s | candidates=%-4d | chosen=%-30s score=%.3f\n",
                    t.round, t.mrXPosition, t.detectivePositions,
                    t.candidateSetSize, moveStr(t.chosenMove), t.chosenScore));
                for (int i = 0; i < t.top3Alternatives.size(); i++) {
                    Map.Entry<Move, Double> e = t.top3Alternatives.get(i);
                    sb.append(String.format(
                        "       alt%d: %-30s score=%.3f%s\n",
                        i + 1, moveStr(e.getKey()), e.getValue(),
                        e.getKey().equals(t.chosenMove) ? " <-- CHOSEN" : ""));
                }
            }
            return sb.toString();
        }

        private static String moveStr(Move m) {
            if (m == null) return "null";
            return m.toString();
        }
    }

    // ---- Static active-logger slot (one game at a time) ----

    private static volatile MoveLogger active = null;

    public static void setActive(MoveLogger logger) { active = logger; }
    public static MoveLogger getActive() { return active; }
    public static void clearActive() { active = null; }

    // ---- Instance ----

    private final List<GameLog> logs = new ArrayList<>();
    private GameLog current = null;

    public void startGame(int gameIndex) {
        current = new GameLog(gameIndex);
        logs.add(current);
    }

    public void endGame(boolean mrXWon, int round) {
        if (current != null) current.setOutcome(mrXWon, round);
        current = null;
    }

    public void recordTurn(int round, int mrXPosition, List<Integer> detectivePositions,
                           Move chosenMove, double chosenScore,
                           List<Map.Entry<Move, Double>> top3, int candidateSetSize) {
        if (current != null) {
            current.turns.add(new TurnRecord(round, mrXPosition, detectivePositions,
                chosenMove, chosenScore, top3, candidateSetSize));
        }
    }

    public List<GameLog> getLogs() { return Collections.unmodifiableList(logs); }

    /** Returns only losing games. */
    public List<GameLog> getLosingGames() {
        List<GameLog> result = new ArrayList<>();
        for (GameLog g : logs) {
            if ("DETECTIVE_WIN".equals(g.outcome)) result.add(g);
        }
        return result;
    }
}

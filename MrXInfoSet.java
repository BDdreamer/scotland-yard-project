package uk.ac.bris.cs.scotlandyard.ai;

import java.util.*;

public class MrXInfoSet {

    public static String buildKey(int mrxPos,
                                   Set<Integer> detectivePositions,
                                   int round,
                                   boolean isRevealRound,
                                   int secretTickets,
                                   int doubleTickets,
                                   GraphAnalyzer graphAnalyzer) {

        // Cap tickets to match Python training range
        int cappedSecret = Math.min(secretTickets, 2);
        int cappedDouble = Math.min(doubleTickets, 2);

        // Distances capped at 5, sorted
        int[] dists = new int[detectivePositions.size()];
        int i = 0;
        for (int detPos : detectivePositions) {
            int d = graphAnalyzer.calculateShortestPath(mrxPos, detPos);
            dists[i++] = Math.min(d < 0 ? 5 : d, 5);
        }
        Arrays.sort(dists);

        // Close count
        int closeCount = 0;
        for (int d : dists) if (d <= 2) closeCount++;

        // Phase
        int phase = round <= 8 ? 0 : round <= 16 ? 1 : 2;

        // Build string matching Python format (WITHOUT numLegalMoves)
        return String.format("(%s, %d, %d, %s, %d, %d)",
                toTuple(dists),
                closeCount,
                phase,
                isRevealRound ? "True" : "False",
                cappedSecret,
                cappedDouble
        );
    }

    private static String toTuple(int[] arr) {
        StringBuilder sb = new StringBuilder("(");
        for (int j = 0; j < arr.length; j++) {
            sb.append(arr[j]);
            if (j < arr.length - 1) sb.append(", ");
        }
        sb.append(")");
        return sb.toString();
    }
}
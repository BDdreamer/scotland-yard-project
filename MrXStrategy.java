package uk.ac.bris.cs.scotlandyard.ai;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class MrXStrategy {

    // Key = infoSet WITHOUT numLegalMoves
    private final Map<String, int[]> indices = new HashMap<>();
    private final Map<String, double[]> probs = new HashMap<>();
    private int hits = 0;
    private int misses = 0;

    public void load(String jsonPath) throws Exception {
        String content = Files.readString(Path.of(jsonPath));
        JsonObject json = JsonParser.parseString(content).getAsJsonObject();

        for (String fullKey : json.keySet()) {
            // Strip numLegalMoves from key
            String infoSetKey = extractInfoSetKey(fullKey);
            if (infoSetKey == null) continue;

            JsonObject node = json.getAsJsonObject(fullKey);
            JsonArray idxArr = node.getAsJsonArray("indices");
            JsonArray probArr = node.getAsJsonArray("probs");

            int[] idx = new int[idxArr.size()];
            double[] prob = new double[probArr.size()];
            for (int i = 0; i < idx.length; i++) {
                idx[i] = idxArr.get(i).getAsInt();
                prob[i] = probArr.get(i).getAsDouble();
            }

            // If duplicate infoSet key, keep higher probability entry
            if (!indices.containsKey(infoSetKey)) {
                indices.put(infoSetKey, idx);
                probs.put(infoSetKey, prob);
            }
        }
        System.out.println("[CFR] Loaded " + indices.size() + " strategy nodes");
    }

/**
     * Extracts infoSet key without numLegalMoves.
     * Input:  "(((4, 5, 5, 5, 5), 4, 0, False, 2, 2), 104)"
     * Output: "((4, 5, 5, 5, 5), 4, 0, False, 2, 2)"
     */
    private String extractInfoSetKey(String fullKey) {
        try {
            // Find the last ", N)" where N is move count, strip it and the outer ((
            // "(((4, 5, 5, 5, 5), 4, 0, False, 2, 2), 104)"
            // → "((4, 5, 5, 5, 5), 4, 0, False, 2, 2)"
            
            // Find the last occurrence of "), " to separate infoSet from move count
            int lastInfoSep = fullKey.lastIndexOf("), ");
            if (lastInfoSep == -1) return null;
            
            // Extract everything before ", N)" - that's our infoSet key
            // "(((4, 5, 5, 5, 5), 0, 0, False, 2, 2), 104)" → "(((4, 5, 5, 5, 5), 0, 0, False, 2, 2)"
            String inner = fullKey.substring(0, lastInfoSep + 1);
            // Remove the first two opening parens "((" to get one "("
            if (inner.startsWith("(((")) {
                inner = inner.substring(1);  // Remove first "("
            }
            return inner;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean hasKey(String infoSetKey) {
        return indices.containsKey(infoSetKey);
    }

    public int selectMove(String infoSetKey, int numLegalMoves, Random rng) {
        if (indices.containsKey(infoSetKey)) {
            hits++;
            int[] idx = indices.get(infoSetKey);
            double[] prob = probs.get(infoSetKey);

            // Normalize
            double sum = 0;
            for (double p : prob) sum += p;

            double r = rng.nextDouble() * sum;
            double cumulative = 0;
            for (int i = 0; i < prob.length; i++) {
                cumulative += prob[i];
                if (r <= cumulative) {
                    return idx[i] < numLegalMoves ? idx[i] : rng.nextInt(numLegalMoves);
                }
            }
            return idx[idx.length - 1] < numLegalMoves
                   ? idx[idx.length - 1]
                   : rng.nextInt(numLegalMoves);
        }
        misses++;
        return rng.nextInt(numLegalMoves);
    }

    public void printStats() {
        int total = hits + misses;
        System.out.printf("[CFR] Hit rate: %d/%d (%.1f%%)%n",
                hits, total, total == 0 ? 0 : 100.0 * hits / total);
    }
}
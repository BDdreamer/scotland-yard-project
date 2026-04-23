package uk.ac.bris.cs.scotlandyard.ai;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs games until 5 losses are collected, logging every Mr. X move with full state.
 *
 * Run via:
 *   mvnw.cmd -q test-compile dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=cp.txt
 *   java -Xmx4g -cp "target\test-classes;target\classes;<cp>" uk.ac.bris.cs.scotlandyard.ai.DiagnosticRunner
 *
 * Output: diagnostic_losses.txt
 */
public class DiagnosticRunner {

    private static final int TARGET_LOSSES = 5;
    private static final int MAX_GAMES     = 60;  // safety cap
    private static final long SEED         = 42L;
    private static final String OUT_FILE   = "diagnostic_losses.txt";

    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(System.out, true));

        MoveLogger logger = new MoveLogger();
        MoveLogger.setActive(logger);

        SimulationRunner.SimulationConfig config = new SimulationRunner.SimulationConfig();
        config.numGames              = 1;
        config.useTimeLimit          = false;
        config.collectMetrics        = false;
        config.logProgress           = false;
        config.useCoordinatedDetectives = true;
        config.seed                  = SEED;

        int gamesPlayed = 0;
        int lossesFound = 0;

        java.util.Random rng = new java.util.Random(SEED);

        while (lossesFound < TARGET_LOSSES && gamesPlayed < MAX_GAMES) {
            int gameIndex = gamesPlayed + 1;
            logger.startGame(gameIndex);

            config.seed = SEED + gamesPlayed;
            SimulationRunner.SimulationResults results = SimulationRunner.runSimulation(config);

            boolean mrXWon = results.mrXWins > 0;
            int finalRound = (int) Math.round(results.avgRoundsSurvived);
            logger.endGame(mrXWon, finalRound);

            gamesPlayed++;
            if (!mrXWon) lossesFound++;

            System.out.printf("Game %d: %s (round %d) | losses so far: %d%n",
                gameIndex, mrXWon ? "WIN" : "LOSS", finalRound, lossesFound);
        }

        MoveLogger.clearActive();

        List<MoveLogger.GameLog> losses = logger.getLosingGames();
        System.out.printf("%nCollected %d losing games out of %d played.%n", losses.size(), gamesPlayed);

        try (PrintWriter out = new PrintWriter(new FileWriter(OUT_FILE))) {
            out.println("=== DIAGNOSTIC: MR. X LOSING GAME ANALYSIS ===");
            out.printf("Games played: %d | Losses logged: %d%n%n", gamesPlayed, losses.size());

            for (MoveLogger.GameLog log : losses) {
                out.println(log.format());
                out.println();
            }
        }

        System.out.println("Written to " + OUT_FILE);
    }
}

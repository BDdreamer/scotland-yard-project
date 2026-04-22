package uk.ac.bris.cs.scotlandyard.ai;

/**
 * Standalone benchmark entry point for long-running Mr. X vs coordinated detective experiments.
 *
 * Run via Maven exec plugin (test classpath) to bypass surefire fork timeouts:
 *   mvnw.cmd org.codehaus.mojo:exec-maven-plugin:1.6.0:java
 *     -Dexec.classpathScope=test
 *     -Dexec.mainClass=uk.ac.bris.cs.scotlandyard.ai.BenchmarkRunner
 *     -Dbench.games=20
 *     -Dbench.metrics=false
 */
public final class BenchmarkRunner {

    private BenchmarkRunner() {}

    public static void main(String[] args) {
        int games = Integer.getInteger("bench.games", 20);
        boolean collectMetrics = Boolean.parseBoolean(System.getProperty("bench.metrics", "true"));
        boolean logProgress = Boolean.parseBoolean(System.getProperty("bench.logProgress", "false"));
        long seed = Long.getLong("bench.seed", 42L);

        System.out.println("=== CoordinatedDetectiveAI Benchmark Runner ===");
        System.out.println("games=" + games
            + ", metrics=" + collectMetrics
            + ", logProgress=" + logProgress
            + ", seed=" + seed);

        SimulationRunner.SimulationConfig config = new SimulationRunner.SimulationConfig();
        config.numGames = games;
        config.useTimeLimit = false;
        config.collectMetrics = collectMetrics;
        config.logProgress = logProgress;
        config.useCoordinatedDetectives = true;
        config.seed = seed;

        long startMs = System.currentTimeMillis();
        SimulationRunner.SimulationResults results = SimulationRunner.runSimulation(config);
        long elapsedMs = System.currentTimeMillis() - startMs;

        System.out.println("\n=== BENCHMARK RESULTS ===");
        System.out.println(results.getDetailedSummary());
        System.out.println("Total time: " + elapsedMs + "ms");
        System.out.println("Avg per game: " + (results.totalGames == 0 ? 0 : (elapsedMs / results.totalGames)) + "ms");
        System.out.println("Mr X Win Rate: " + String.format("%.2f%%", results.getMrXWinRate() * 100.0));
        // Structured output for automation/aggregation scripts.
        System.out.println("RESULT games=" + results.totalGames
            + " mrXWins=" + results.mrXWins
            + " detectiveWins=" + results.detectiveWins
            + " seed=" + seed
            + " elapsedMs=" + elapsedMs);
    }
}

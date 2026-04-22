package uk.ac.bris.cs.scotlandyard.ai;

import uk.ac.bris.cs.scotlandyard.model.*;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Automatically logs game results for analysis.
 * Captures detailed information about how Mr. X wins or gets caught.
 */
public class GameLogger implements Spectator {
    
    private final GraphAnalyzer graphAnalyzer;
    private final String logFilePath;
    private int gameNumber = 1;
    private int currentRound = 0;
    private Map<Colour, Integer> lastKnownPositions = new HashMap<>();
    private List<String> gameLog = new ArrayList<>();
    
    public GameLogger(GraphAnalyzer graphAnalyzer, String logFilePath) {
        this.graphAnalyzer = graphAnalyzer;
        this.logFilePath = logFilePath;
        
        // Initialize log file
        try {
            Files.write(Paths.get(logFilePath), 
                Arrays.asList("# Automated Game Log", 
                             "Generated: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                             "",
                             "---",
                             ""));
        } catch (IOException e) {
            System.err.println("Failed to initialize log file: " + e.getMessage());
        }
    }
    
    @Override
    public void onMoveMade(ScotlandYardView view, Move move) {
        currentRound = view.getCurrentRound();
        
        // Track positions
        for (Colour colour : view.getPlayers()) {
            view.getPlayerLocation(colour).ifPresent(loc -> 
                lastKnownPositions.put(colour, loc));
        }
    }
    
    @Override
    public void onRotationComplete(ScotlandYardView view) {
        // Update round
        currentRound = view.getCurrentRound();
    }
    
    @Override
    public void onGameOver(ScotlandYardView view, Set<Colour> winningPlayers) {
        // Analyze the game result
        boolean mrXWon = winningPlayers.contains(Colour.BLACK);
        int finalRound = view.getCurrentRound();
        
        StringBuilder log = new StringBuilder();
        log.append("\n## Game ").append(gameNumber).append("\n\n");
        log.append("- Mr. X won? ").append(mrXWon ? "Y" : "N").append("\n");
        
        if (!mrXWon) {
            // Mr. X was caught - analyze how
            int mrXFinal = view.getPlayerLocation(Colour.BLACK).orElse(0);
            log.append("- Caught at round? ").append(finalRound).append("\n");
            
            // Get detective positions
            List<Integer> detPositions = new ArrayList<>();
            for (Colour colour : view.getPlayers()) {
                if (colour.isDetective()) {
                    view.getPlayerLocation(colour).ifPresent(detPositions::add);
                }
            }
            
            // Calculate distances
            int minDistance = Integer.MAX_VALUE;
            boolean intercepted = false;
            for (int detPos : detPositions) {
                if (detPos == mrXFinal) {
                    intercepted = true;
                } else {
                    int dist = graphAnalyzer.calculateShortestPath(mrXFinal, detPos);
                    if (dist >= 0) {
                        minDistance = Math.min(minDistance, dist);
                    }
                }
            }
            
            // Count remaining tickets
            int totalTickets = 0;
            totalTickets += view.getPlayerTickets(Colour.BLACK, Ticket.TAXI).orElse(0);
            totalTickets += view.getPlayerTickets(Colour.BLACK, Ticket.BUS).orElse(0);
            totalTickets += view.getPlayerTickets(Colour.BLACK, Ticket.UNDERGROUND).orElse(0);
            totalTickets += view.getPlayerTickets(Colour.BLACK, Ticket.SECRET).orElse(0);
            totalTickets += view.getPlayerTickets(Colour.BLACK, Ticket.DOUBLE).orElse(0);
            
            // Determine capture method
            String captureMethod;
            if (intercepted) {
                captureMethod = "intercepted";
            } else if (totalTickets == 0) {
                captureMethod = "ticket exhaustion";
            } else if (minDistance <= 1) {
                // Check if it was after a reveal
                boolean wasRevealRound = (finalRound == 3 || finalRound == 8 || 
                                         finalRound == 13 || finalRound == 18 || 
                                         finalRound == 24);
                boolean afterReveal = (finalRound - 1 == 3 || finalRound - 1 == 8 ||
                                      finalRound - 1 == 13 || finalRound - 1 == 18 || 
                                      finalRound - 1 == 24);
                if (wasRevealRound || afterReveal) {
                    captureMethod = "surrounded after reveal";
                } else {
                    captureMethod = "cornered";
                }
            } else {
                captureMethod = "cornered";
            }
            
            log.append("- How caught? ").append(captureMethod).append("\n");
            log.append("\n### Details:\n");
            log.append("- Mr. X final position: ").append(mrXFinal).append("\n");
            log.append("- Detective positions: ").append(detPositions).append("\n");
            log.append("- Min distance to detective: ").append(minDistance).append("\n");
            log.append("- Tickets remaining: ").append(totalTickets).append("\n");
            
        } else {
            log.append("- Caught at round? N/A (Mr. X won)\n");
            log.append("- How caught? N/A (Mr. X won)\n");
            log.append("\n### Details:\n");
            log.append("- Mr. X survived all ").append(finalRound).append(" rounds\n");
        }
        
        log.append("\n---\n");
        
        // Write to file
        try {
            Files.write(Paths.get(logFilePath), 
                       log.toString().getBytes(), 
                       StandardOpenOption.APPEND);
            System.out.println("\n[GameLogger] Game " + gameNumber + " logged to " + logFilePath);
        } catch (IOException e) {
            System.err.println("Failed to write log: " + e.getMessage());
        }
        
        gameNumber++;
    }
    
    @Override
    public void onRoundStarted(ScotlandYardView view, int round) {
        currentRound = round;
    }
}

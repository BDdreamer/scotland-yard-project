package uk.ac.bris.cs.scotlandyard.ai;

/**
 * Centralized configuration for all scoring weights and thresholds.
 * 
 * This class extracts magic numbers from the codebase to make tuning easier
 * and provide clear documentation for each weight's purpose.
 * 
 * All weights are public static final for easy access and modification.
 */
public class ScoringConfig {
    
    // ==================== DESTINATION SAFETY ====================
    
    /** Weight for node connectivity (number of exits) */
    public static final double CONNECTIVITY_WEIGHT = 15.0;
    
    /** Weight for transport type diversity at a node */
    public static final double TRANSPORT_DIVERSITY_WEIGHT = 12.0;
    
    /** Multiplier for destination safety score */
    public static final double DESTINATION_SAFETY_MULTIPLIER = 2.0;
    
    // ==================== DISTANCE BONUSES ====================
    
    /** Weight for distance from detectives */
    public static final double DISTANCE_BONUS_WEIGHT = 15.0;  // Increased from 10.0 for coordinated detectives
    
    /** Bonus for being close to detectives (risky but high reward) */
    public static final double DISTANCE_BONUS_CLOSE = 5.0;  // Reduced from 10.0 - too risky against coordinated detectives
    
    /** Bonus for being far from detectives (safe positioning) */
    public static final double DISTANCE_BONUS_FAR = 50.0;  // Increased from 30.0 - prioritize safety against coordination
    
    /** Distance threshold for "close" positioning */
    public static final int DISTANCE_CLOSE_THRESHOLD = 3;
    
    /** Distance threshold for "far" positioning */
    public static final int DISTANCE_FAR_THRESHOLD = 8;
    
    // ==================== ESCAPE & MOBILITY ====================
    
    /** Weight for effective exits (weighted by transport type) */
    public static final double EFFECTIVE_EXITS_WEIGHT = 3.0;
    
    /** Base weight for escape volume (reachable area) */
    public static final double ESCAPE_VOLUME_BASE_WEIGHT = 5.0;
    
    /** Maximum escape volume weight (scales with detective count) */
    public static final double ESCAPE_VOLUME_MAX_WEIGHT = 12.0;
    
    /** Weight for future mobility (2-step reachability) */
    public static final double FUTURE_MOBILITY_WEIGHT = 8.0;
    
    // ==================== PENALTIES ====================
    
    /** Penalty for being enclosed/trapped */
    public static final double ENCLOSURE_PENALTY = -60.0;
    
    /** Base hub penalty when hub is contested */
    public static final double HUB_PENALTY_CONTESTED = -80.0;
    
    /** Hub penalty when hub is relatively safe */
    public static final double HUB_PENALTY_SAFE = -30.0;
    
    /** Penalty for pincer/flanking danger */
    public static final double PINCER_DANGER_PENALTY = -45.0;
    
    /** Weight for interception risk */
    public static final double INTERCEPTION_RISK_WEIGHT = 0.8;
    
    /** Weight for contest ratio (how many detectives can reach this node) */
    public static final double CONTEST_RATIO_WEIGHT = 40.0;
    
    // ==================== TICKET SCORING ====================
    
    /** Base ticket conservation weight */
    public static final double TICKET_CONSERVATION_BASE_WEIGHT = 3.0;
    
    /** Maximum ticket conservation weight (adaptive) */
    public static final double TICKET_CONSERVATION_MAX_WEIGHT = 4.5;
    
    /** Penalty for using SECRET at single-transport node */
    public static final double SECRET_SINGLE_TRANSPORT_PENALTY = -150.0;
    
    /** Bonus for using SECRET before reveal (close to detectives) */
    public static final double SECRET_PRE_REVEAL_BONUS_CLOSE = 100.0;
    
    /** Bonus for using SECRET before reveal (medium distance) */
    public static final double SECRET_PRE_REVEAL_BONUS_MEDIUM = 75.0;
    
    /** Bonus for using SECRET before reveal (far from detectives) */
    public static final double SECRET_PRE_REVEAL_BONUS_FAR = 50.0;
    
    /** Stack bonus for using SECRET 2 moves before reveal */
    public static final double SECRET_STACK_BONUS = 120.0;
    
    /** Penalty for using reserved SECRET on non-reveal rounds */
    public static final double SECRET_RESERVATION_PENALTY = -60.0;
    
    /** Penalty for using DOUBLE on reveal round */
    public static final double DOUBLE_ON_REVEAL_PENALTY = -150.0;
    
    /** Penalty for bus corridor funnel (≤2 bus connections) */
    public static final double BUS_FUNNEL_PENALTY = -30.0;
    
    /** Penalty for underground isolation (≤1 underground connection) */
    public static final double UNDERGROUND_ISOLATION_PENALTY = -40.0;
    
    /** Bonus for good underground connectivity (≥3 connections) */
    public static final double UNDERGROUND_CONNECTIVITY_BONUS = 20.0;
    
    /** Extra penalty for underground isolation near reveals */
    public static final double UNDERGROUND_ISOLATION_NEAR_REVEAL_PENALTY = -30.0;
    
    // ==================== HISTORICAL & LEARNING ====================
    
    /** Weight for historical safety score (learned from past games) */
    public static final double HISTORICAL_SAFETY_WEIGHT = 15.0;
    
    /** Weight for persistent safety score (cross-game learning) */
    public static final double PERSISTENT_SAFETY_WEIGHT = 10.0;
    
    // ==================== THRESHOLDS ====================
    
    /** Minimum detective distance to consider "close" */
    public static final int MIN_DETECTIVE_DISTANCE_CLOSE = 4;
    
    /** Minimum detective distance to consider "medium" */
    public static final int MIN_DETECTIVE_DISTANCE_MEDIUM = 6;
    
    /** Game progress threshold for late game (0.7 = 70% complete) */
    public static final double LATE_GAME_THRESHOLD = 0.7;
    
    /** Encirclement risk threshold for escape mode */
    public static final double ENCIRCLEMENT_ESCAPE_THRESHOLD = 60.0;
    
    /** Bus neighbor threshold for funnel detection */
    public static final int BUS_FUNNEL_THRESHOLD = 2;
    
    /** Underground neighbor threshold for isolation */
    public static final int UNDERGROUND_ISOLATION_THRESHOLD = 1;
    
    /** Underground neighbor threshold for good connectivity */
    public static final int UNDERGROUND_GOOD_CONNECTIVITY_THRESHOLD = 3;
    
    // ==================== BELIEF STATE & AMBIGUITY ====================
    
    /** Candidate set size threshold for "cognitive overload" */
    public static final int CANDIDATE_SET_OVERLOAD = 20;
    
    /** Candidate set size threshold for "very difficult" */
    public static final int CANDIDATE_SET_VERY_DIFFICULT = 15;
    
    /** Candidate set size threshold for "moderately difficult" */
    public static final int CANDIDATE_SET_MODERATE = 10;
    
    /** Candidate set size threshold for "easy" */
    public static final int CANDIDATE_SET_EASY = 5;
    
    /** Score for cognitive overload (>20 candidates) */
    public static final double COGNITIVE_OVERLOAD_SCORE = 100.0;
    
    /** Score for very difficult tracking (>15 candidates) */
    public static final double COGNITIVE_VERY_DIFFICULT_SCORE = 80.0;
    
    /** Score for moderate difficulty (>10 candidates) */
    public static final double COGNITIVE_MODERATE_SCORE = 50.0;
    
    /** Score for easy tracking (>5 candidates) */
    public static final double COGNITIVE_EASY_SCORE = 20.0;
    
    // ==================== SURFACE TURN PLANNING ====================
    
    /** Weight for inter-reveal distance (distance between reveal positions) */
    public static final double INTER_REVEAL_DISTANCE_WEIGHT = 20.0;
    
    /** Bonus for being in different transport zone on reveal */
    public static final double DIFFERENT_TRANSPORT_ZONE_BONUS = 50.0;
    
    // ==================== ZONE STRATEGY ====================
    
    /** Weight for average neighbor connectivity in zone safety */
    public static final double ZONE_AVG_NEIGHBOR_CONNECTIVITY_WEIGHT = 5.0;
    
    /** Weight for centrality in zone safety */
    public static final double ZONE_CENTRALITY_WEIGHT = 3.0;
    
    /** Weight for escape volume in zone safety */
    public static final double ZONE_ESCAPE_VOLUME_WEIGHT = 2.0;
    
    // ==================== MCTS PARAMETERS ====================
    
    /** Exploration constant for UCB1 (typically √2 ≈ 1.414) */
    public static final double MCTS_EXPLORATION_CONSTANT = Math.sqrt(2);
    
    /** Number of MCTS iterations per move */
    public static final int MCTS_ITERATIONS = 1000;
    
    /** Simulation depth for MCTS rollouts */
    public static final int MCTS_SIMULATION_DEPTH = 6;  // Increased from 3 for better lookahead against coordinated detectives
    
    /** Time budget per move in milliseconds */
    public static final long TIME_BUDGET_MS = 800;  // Increased from 400ms for better search quality
    
    // ==================== SEARCH PARAMETERS ====================
    
    /** Maximum search depth for iterative deepening */
    public static final int MAX_SEARCH_DEPTH = 6;
    
    /** Minimum search depth */
    public static final int MIN_SEARCH_DEPTH = 2;
    
    // ==================== CACHE PARAMETERS ====================
    
    /** Maximum size for shortest path cache */
    public static final int CACHE_MAX_SIZE = 20_000;
    
    // ==================== UTILITY METHODS ====================
    
    /**
     * Calculate adaptive ticket conservation weight based on game progress.
     * Weight increases as game progresses to conserve tickets for endgame.
     * 
     * @param gameProgress 0.0 to 1.0 representing game completion
     * @return Adaptive weight between BASE and MAX
     */
    public static double getAdaptiveTicketWeight(double gameProgress) {
        return TICKET_CONSERVATION_BASE_WEIGHT + 
               (TICKET_CONSERVATION_MAX_WEIGHT - TICKET_CONSERVATION_BASE_WEIGHT) * gameProgress;
    }
    
    /**
     * Calculate adaptive escape volume weight based on detective count.
     * More detectives = higher weight on escape volume.
     * 
     * @param detectiveCount Number of active detectives
     * @return Adaptive weight between BASE and MAX
     */
    public static double getAdaptiveEscapeVolumeWeight(int detectiveCount) {
        // Assuming 5 detectives max
        double ratio = Math.min(detectiveCount / 5.0, 1.0);
        return ESCAPE_VOLUME_BASE_WEIGHT + 
               (ESCAPE_VOLUME_MAX_WEIGHT - ESCAPE_VOLUME_BASE_WEIGHT) * ratio;
    }
    
    /**
     * Calculate adaptive hub penalty based on contest level.
     * More contested = higher penalty.
     * 
     * @param isContested Whether the hub is contested by detectives
     * @return Appropriate hub penalty
     */
    public static double getAdaptiveHubPenalty(boolean isContested) {
        return isContested ? HUB_PENALTY_CONTESTED : HUB_PENALTY_SAFE;
    }
    
    /**
     * Get SECRET bonus based on detective distance and game progress.
     * 
     * @param minDetectiveDistance Shortest path to nearest detective
     * @param gameProgress 0.0 to 1.0 representing game completion
     * @return Appropriate bonus value
     */
    public static double getSecretPreRevealBonus(int minDetectiveDistance, double gameProgress) {
        boolean isLateGame = gameProgress > LATE_GAME_THRESHOLD;
        
        if (minDetectiveDistance <= MIN_DETECTIVE_DISTANCE_CLOSE) {
            return isLateGame ? SECRET_PRE_REVEAL_BONUS_CLOSE : SECRET_PRE_REVEAL_BONUS_CLOSE - 10.0;
        } else if (minDetectiveDistance <= MIN_DETECTIVE_DISTANCE_MEDIUM) {
            return isLateGame ? SECRET_PRE_REVEAL_BONUS_MEDIUM : SECRET_PRE_REVEAL_BONUS_MEDIUM - 10.0;
        } else {
            return isLateGame ? SECRET_PRE_REVEAL_BONUS_FAR : SECRET_PRE_REVEAL_BONUS_FAR - 10.0;
        }
    }
    
    /**
     * Calculate information entropy for belief state.
     * Shannon entropy: -Σ(p * log₂(p)) over candidate set.
     * 
     * @param candidateCount Number of possible Mr. X locations
     * @return Bits of uncertainty (higher = more ambiguous)
     */
    public static double calculateInformationEntropy(int candidateCount) {
        if (candidateCount <= 0) return 0.0;
        if (candidateCount == 1) return 0.0;
        
        double probPerLocation = 1.0 / candidateCount;
        return -Math.log(probPerLocation) / Math.log(2);  // Bits of uncertainty
    }
    
    // Private constructor to prevent instantiation
    private ScoringConfig() {
        throw new AssertionError("ScoringConfig is a utility class and should not be instantiated");
    }
}

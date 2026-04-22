package uk.ac.bris.cs.scotlandyard.ai;

import uk.ac.bris.cs.scotlandyard.model.Move;
import uk.ac.bris.cs.scotlandyard.model.Ticket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCTS (Monte Carlo Tree Search) Node for Mr. X move selection.
 * 
 * Each node represents a game state (Mr. X position) and tracks:
 * - visits: how many simulations passed through this node
 * - totalScore: cumulative score from all simulations
 * - children: possible next moves from this position
 *
 * UCB1 (Upper Confidence Bound) balances exploration vs exploitation.
 */
class MCTSNode {
    final int mrXPosition;
    final Move moveFromParent;  // null for root
    MCTSNode parent;      // null for root
    Map<Ticket, Integer> remainingTickets;
    List<MCTSNode> children = new ArrayList<>();
    int visits = 0;
    double totalScore = 0.0;
    
    /**
     * Constructor for root node
     */
    MCTSNode(int mrXPosition, Map<Ticket, Integer> remainingTickets) {
        this.mrXPosition = mrXPosition;
        this.moveFromParent = null;
        this.parent = null;
        this.remainingTickets = remainingTickets;
    }
    
    /**
     * Constructor for child nodes
     */
    MCTSNode(int mrXPosition, Move moveFromParent, MCTSNode parent, Map<Ticket, Integer> remainingTickets) {
        this.mrXPosition = mrXPosition;
        this.moveFromParent = moveFromParent;
        this.parent = parent;
        this.remainingTickets = remainingTickets;
    }
    
    /**
     * Constructor for expansion nodes (no move, used for tree growth)
     */
    MCTSNode(int mrXPosition, double expansionScore, MCTSNode parent, Map<Ticket, Integer> remainingTickets) {
        this.mrXPosition = mrXPosition;
        this.moveFromParent = null;
        this.parent = parent;
        this.remainingTickets = remainingTickets;
        this.totalScore = expansionScore;
    }
    
    /**
     * UCB1 (Upper Confidence Bound) formula for node selection.
     * 
     * Balances:
     * - Exploitation: totalScore / visits (average score)
     * - Exploration: sqrt(log(parent.visits) / visits) (visit less-explored nodes)
     * 
     * @param explorationConstant typically √2 (1.414), tune based on testing
     * @return UCB1 value (higher = more promising)
     */
    double UCB1(double explorationConstant) {
        if (visits == 0) return Double.POSITIVE_INFINITY;  // Unvisited nodes have highest priority
        
        double exploitation = totalScore / visits;
        double exploration = explorationConstant * Math.sqrt(Math.log(parent.visits) / visits);
        
        return exploitation + exploration;
    }
    
    /**
     * @return true if this node has no children (leaf node)
     */
    boolean isLeaf() {
        return children.isEmpty();
    }
    
    /**
     * @return average score per visit
     */
    double getAverageScore() {
        return visits == 0 ? 0.0 : totalScore / visits;
    }
}

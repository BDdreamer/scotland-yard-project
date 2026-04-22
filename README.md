1. Fix the MCTS Rollout Realism Gap (HIGH IMPACT)
This is your biggest single problem. The simulateGreedyMrXMove rollout policy is far too simplistic — it only looks at distance and mobility. Meanwhile, the detectives in your rollouts use DetectiveSimulator which mirrors the real coordinated AI with roles (INTERCEPTOR, BLOCKER, ENCIRCLER, CHASER). The asymmetry means MCTS over-estimates survival because rollout-Mr.X makes dumb moves, then gets caught, biasing the tree toward positions that look safe but aren't.
Fix: Make the rollout Mr.X policy aware of encirclement. Add cordon detection to simulateGreedyMrXMove — even a cheap contestedExits / totalExits ratio check would dramatically improve rollout accuracy. Also, the rollout currently ignores ticket-type information hiding (SECRET usage), which is Mr.X's main weapon.
2. The evaluateTerminalPosition is Too Binary (HIGH IMPACT)
javaif (encirclement > 50) return 0.0;  // Trapped → loss
This hard cutoff at 50% encirclement throws away enormous amounts of gradient information. An encirclement of 51% scores identically to being literally captured. Change this to a smooth sigmoid like 1.0 / (1.0 + Math.exp((encirclement - 50) / 10.0)) so the MCTS tree can differentiate between "slightly surrounded" and "totally trapped."
3. Introduce Lookahead-Aware Escape Corridors (STRUCTURAL)
Your AI reacts to encirclement after it's forming. The coordinated detectives assign roles proactively (ENCIRCLER, BLOCKER). Mr.X needs a corridor planner that identifies escape routes 3–4 moves ahead and biases toward them before the net closes. Concretely:

Before each move, compute the 2-step reachable set from each candidate destination
For each reachable node, check how many detectives can reach it in ≤2 moves
A "corridor" is a path where ≥2 consecutive nodes have low detective coverage
Bonus moves that lead toward open corridors; penalize moves that cut off corridors

4. Exploit the Detectives' Coordination Weakness (STRUCTURAL)
Reading CoordinatedDetectiveAI.scoreMove, the detectives' scoring is simplistic: W1=2.0 * covered + W2=1.0 * proximity + W3=0.5 * hubValue. The coordination penalty for overlap is only 5.0. This means:

Decoy moves work: Moving toward a cluster of candidates then using SECRET to go the opposite direction will pull INTERCEPTOR and CHASER in the wrong direction for at least 1–2 rounds
Split the formation: The detectives assign roles based on a centroid. If Mr.X moves to positions that create a bimodal candidate distribution (two clusters far apart), the role assignment breaks down — CHASER goes to centroid (between clusters, covering neither), INTERCEPTOR goes to one cluster, leaving the other open
Implement a "candidate splitting" bonus that rewards moves making the belief state centroid far from all actual candidates

5. Double Move Timing as Encirclement Counter (MEDIUM IMPACT)
Your DOUBLE move evaluation penalizes early usage but doesn't specifically reward using DOUBLE to break through a forming encirclement. When detectives are 2–3 moves out and closing:

A well-timed DOUBLE lets Mr.X jump through the net before it closes
Score DOUBLE moves with a "net-breaking" bonus: if the first leg moves toward the thinnest part of the detective formation and the second leg moves through it, give a large bonus
This directly counters the ENCIRCLER role, which needs 2+ rounds to complete coverage

6. Time Budget is Too Low (QUICK WIN)
TIME_BUDGET_MS = 800 with only 6 determinizations per rollout at depth 6–12 means MCTS is barely getting enough iterations to converge. If your benchmark allows it, bump to 1500–2000ms. Even within 800ms, you can get more signal by reducing determinizations from 6 to 3 (diminishing returns past 3–4) and using the saved time for more MCTS iterations.

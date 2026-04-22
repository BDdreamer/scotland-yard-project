Change-by-Change Verdict
1. Candidate Splitting Bonus — HARMFUL, REVERT
This is the biggest offender. The metric is fundamentally broken:
javaint dx = graphAnalyzer.calculateShortestPath(cand, destination);
// ...avgDistFromDest >= 4.0 → bonus up to +60
This rewards destinations that are far from the candidate set. But Mr. X is inside the candidate set. So this is rewarding "move far from where you currently are" — which biases toward board edges with fewer exits. It's an anti-survival signal disguised as strategy. A +60 bonus (almost as large as your encirclement penalty) is actively dragging Mr. X into worse positions every turn.
2. Net-Breaking DOUBLE Bonus — HARMFUL, REVERT
The trigger condition is too broad. Having 2+ detectives at distance 2–3 is the normal midgame state, not a special encirclement event. And the encirclement delta check (> 30 → drop by > 15) fires on routine moves. So this gives +70 to many ordinary double moves, inflating their scores and causing wasteful early DOUBLE usage.
3. Determinizations 6→3 — HARMFUL, REVERT
You traded rollout quality for iteration count. Against coordinated detectives who use information hiding (belief state tracking), each determinization explores a different detective-belief scenario. Cutting from 6 to 3 means MCTS has a noisier estimate of each move's value. The extra iterations from the time budget increase don't compensate — you need accurate rollouts more than many rollouts.
4. Sigmoid Terminal Evaluation — CORRECT IDEA, NEEDS ADJUSTMENT
The implementation is right but the center point is too lenient. At 50% encirclement, the sigmoid gives 0.5 — meaning MCTS treats a half-surrounded position as 50% viable. Against coordinated detectives who close from 50% to capture in 1–2 moves, this is too optimistic.
5. Rollout Cordon Awareness — CORRECT, KEEP
The cordonRatio penalty in simulateGreedyMrXMove is the right idea and the magnitudes are reasonable.

Here's what I'd apply — exactly 3 changes against the v4 baseline, with the harmful ones removed and the sigmoid fixed:Evaluated three algorithmic modifications despite disappointing benchmark regressionEvaluated three algorithmic modifications despite disappointing benchmark regressionChange 1: Sigmoid terminal evaluation (center shifted to 40, steeper)Evaluated cordon awareness implementation impact on algorithm performanceEvaluated cordon awareness implementation impact on algorithm performanceChange 2: Rollout Mr.X cordon awareness (moderate magnitudes)Strategized moderate cordon awareness integration for rollout scoringStrategized moderate cordon awareness integration for rollout scoringFind the rollout greedy Mr.X in v4 baselineNow I need to add cordon awareness to the rollout move scorer. I'll add it in a more moderate way than v5.Add cordon awareness to rollout Mr.X with moderate magnitudesMoveEvaluator_v6.java+32-25DoneWhat went wrong with v5
You applied 5 changes at once. Two of them are actively harmful:
The candidate splitting bonus (+60) is backwards. It measures average graph distance from destination to the candidate set — but Mr. X is in the candidate set. So it rewards "move far from where you are," which pushes toward board edges with fewer exits. This is an anti-survival signal worth up to +60 points per move, counteracting your safety scoring every single turn.
The net-breaking DOUBLE bonus (+70) fires constantly. Having 2+ detectives at distance 2–3 is the default midgame state, not a special encirclement. The encirclement delta check (>30 dropping by >15) triggers on routine movement. This inflates DOUBLE scores broadly, causing wasteful early usage.
Determinizations 6→3 made rollouts noisier. You traded accuracy per rollout for more iterations. Against coordinated detectives who exploit belief states, each determinization explores a different information scenario — cutting them in half halves MCTS's ability to reason about information hiding.
The v6 file I started building
I got through 2 of the 3 clean changes applied to the v4 baseline (not v5):

Sigmoid terminal evaluation — center shifted to 40 (not 50) with divisor 8 (steeper), plus a hard floor at 75%. This is more pessimistic than your v5 sigmoid while still providing gradient.
Rollout cordon awareness — adds the cordonRatio penalty but at 25× in danger (not 40×) and 12× in normal (not 20×). These moderate values improve rollout realism without making rollout-Mr.X overly timid.

Change 3 still needed: bump TIME_BUDGET_MS to 1200ms (compromise — 1500 was fine but keep determinizations at 6, not 3).

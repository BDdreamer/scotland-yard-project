# scotland-yard-project

 Starting Point
Initial Mr. X AI (CombinedAI + MoveEvaluator)

Win rate vs. Coordinated Detectives: ~17%

Primary failure modes: Early interception (rounds 7–12) and being surrounded after reveal rounds.

Observations: The AI was overly focused on maximizing ambiguity (candidate set size) even when under spatial pressure. It conserved high‑value tickets (SECRET/DOUBLE) too conservatively against a team that closes space quickly.

🔍 Initial Diagnosis (Valid & Feasible Changes)
A review of the codebase and failure logs identified three high‑impact, low‑effort areas for improvement:

Issue	Solution
1. Ambiguity over‑prioritised when under pressure	Introduce a survival‑dominant blend when candidate set is small or detectives are close.
2. Ticket conservation too strict vs. coordinated team	Apply coordinated‑mode discounts to SECRET/DOUBLE weights and relax early DOUBLE restrictions.
3. Coordination penalties don't scale with danger	Scale formation/candidate‑dividing penalties by candidate set size (smaller set → higher penalty).
These were implemented as a three‑change package (v2).

🧪 First Benchmark After Initial Changes (v2)
20 games (5 per seed: 100042, 200042, 300042, 400042)

Overall win rate: 5% (1 win out of 20) – a regression from 17%.

Capture rounds: 9–12 (still early).

Capture methods: Dominated by intercepted (60–75%) and surrounded after reveal.

Why the regression?
The changes were too aggressive. The survival blend activated too easily, causing the AI to flee predictably. Ticket discounts were too steep (0.6× SECRET, 0.5× DOUBLE), leading to wasteful early usage. Penalty scaling (up to 3×) over‑penalised otherwise reasonable moves.

🔧 Iterative Fine‑Tuning (v3)
Based on the v2 results, the following adjustments were made:

Parameter	v2 Value	v3 Value
Survival blend trigger	candidateCount ≤ 15 & encirclementRisk > 25 & minDetDist ≤ 4 & effectiveExits ≤ 3	Tightened to candidateCount ≤ 12 & encirclementRisk > 40 & minDetDist ≤ 3 & effectiveExits ≤ 2
Survival weight	0.9 / 0.7	0.8 / 0.6
Ticket discount (SECRET)	0.6×	0.8×
Ticket discount (DOUBLE)	0.5×	0.7×
Penalty scaling cap	3.0×	2.0×
Pattern‑break bonus	+15 (+20 SECRET)	+25 (+30 SECRET)
Early DOUBLE bonuses	-60 / -40	-30 / -20
Result (v3 benchmark):

Win rate: 15% (3/20) – back to near baseline.

Capture rounds: 10–15 (slightly longer survival).

Candidates at reveal: 38–68 (better ambiguity maintenance).

Variance: One seed hit 40%, others 0% or 20%.

Assessment: The direction was correct, but performance was still brittle and seed‑dependent. The AI was surviving longer but not escaping coordinated encirclement.

🎯 Further Parameter Tweaks (v4)
To reduce variance and prevent over‑reaction, another round of fine‑tuning was applied:

Parameter	v3 Value	v4 Value
Survival blend trigger	encirclementRisk > 40	encirclementRisk > 50
Survival weight	0.8 / 0.6	0.7 / 0.5
Coordinated detection	hubCoverage ≥ 3 AND optimalSpacing	hubCoverage ≥ 2 OR optimalSpacing
Early DOUBLE bonuses	-30 / -20	unchanged
Pattern‑break bonus	+25 (+30 SECRET)	unchanged
Result (v4 benchmark):

Win rate: 15% (3/20) – no net improvement.

Capture rounds: Dropped sharply for some seeds (7.2 rounds for seed 100042).

Observation: Tighter survival thresholds caused the AI to delay escape until too late, leading to even earlier captures.

Conclusion: Further parameter tuning alone was insufficient to break through the performance ceiling. The core issue—inability to evade coordinated spatial containment—required a structural addition.

💡 Key Lessons Learned
Heuristic weighting is sensitive. Small changes to survival/ambiguity blend can swing behaviour from recklessly aggressive to overly passive.

Ticket strategy must be adaptive. Against a fast‑closing team, early DOUBLE/SECRET usage is essential, but the timing must be precise.

Penalty scaling needs careful capping. Over‑penalising moves when the candidate set is small can force the AI into predictable flight paths.

Benchmark variance matters. Seed‑specific performance highlights the need for robustness across starting positions, not just average win rate.

Structural improvements are now necessary. Parameter tuning has reached diminishing returns. The next step must be a targeted counter‑strategy against the coordinated detectives' encirclement tactics

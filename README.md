The v6 changes produced a clear improvement: 20% win rate (4/20) vs. 15% baseline.
This confirms the cleanup was effective and the MCTS enhancements are beneficial.

📊 Comparison to Previous Versions
Version	Win Rate	Notes
v4 (baseline)	15% (3/20)	Fine‑tuned parameters
v5	10% (2/20)	Harmful bonuses included
v6	20% (4/20)	Clean MCTS improvements only
Seed 200042 reached 40% wins – the highest single‑seed performance so far.

Seed 400042 remains a stubborn 0% – certain starting configurations are still extremely difficult.

🔍 What the Numbers Tell Us
Metric	v4	v6	Change
Avg capture round	~10–12	~11–15	↑ longer survival
Avg candidates at reveal	38–68	24–61	similar range
Interception rate	60–100%	50–80%	↓ slightly better evasion
Per‑game time	~200s	~500s	↑ due to 1200ms budget
The increased survival time and reduced interception rate suggest the AI is making better escape decisions, especially in mid‑game.

🛠️ What Next?

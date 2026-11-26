# Benchmarks

Measured locally on 2026-11-26 with SeaRobots **0.1.3**, Oracle JDK **25.0.2**, and the engine's
league controller limits. Opponents were executed as black boxes. Their controller source
and bytecode were never inspected.

## Frozen implementation: fresh-seed evaluation

The production sources were frozen before this evaluation. SHA-256 hashes were checked
afterward and are recorded in [source-sha256.csv](benchmarks/source-sha256.csv).

**33 previously unused world seeds**, each played against all four opponents: **132 matches**.
The master seed is hexadecimal `20261126`. Spawn slots alternate within each series; the
same 33 maps are used for each opponent. Each match has a **30 minute simulated time limit**.
All requested games completed, with no exclusions and no controller forfeits.

| Opponent | Wins | Losses | Draws | Win rate |
|---|---:|---:|---:|---:|
| Default | 29 | 1 | 3 | 87.9% |
| Gemini | 6 | 14 | 13 | 18.2% |
| Claude | 24 | 0 | 9 | 72.7% |
| Codex | 30 | 0 | 3 | 90.9% |
| **Total** | **89** | **15** | **28** | **67.4%** |

Of the draws, **20 were mutual destruction** and **8 were timeouts**. A mutual kill is counted
as a draw throughout. Peak measured controller CPU was **1,093 ms for an entire match**,
shared by the submarine and its torpedoes, against the 30,000 ms league budget.

Gemini remains the strongest opponent for this implementation. Long pursuits can exhaust
weapon endurance, and simultaneous exchanges can produce mutual kills. The scoreboard
includes these outcomes.

Full per-game results, world seeds, spawn order, final HP, ammunition usage, and controller
CPU: [holdout.csv](benchmarks/holdout.csv).

Reproduce using the inherited Maven profile:

```sh
mvn -Pmatch33 -Dopponent=all -Dgames=33 -Dseed=20261126 -Dthreads=4 -DcombatMinutes=30
```

The measured run used four separate headless JVMs, one per opponent, with four workers
and a 1 GiB heap each. The Maven command invokes the same engine runner and guardrails;
its replay recording and sequential opponent series can change wall time and CPU readings.
The benchmark does not claim 132 distinct maps or a statistically independent sample of
132 world seeds.

## Full match-duration check

A separate eight-game check used hexadecimal master seed `61000001` and the standard
**120 minute simulated time limit**. All eight matches completed without forfeits:

| Opponent | Wins | Losses | Draws |
|---|---:|---:|---:|
| Default | 2 | 0 | 0 |
| Gemini | 0 | 1 | 1 |
| Claude | 1 | 0 | 1 |
| Codex | 2 | 0 | 0 |
| **Total** | **5** | **1** | **2** |

The limit is a cap; matches end earlier when decided. Results are stored separately in
[full-duration.csv](benchmarks/full-duration.csv).

```sh
mvn -Pmatch33 -Dopponent=all -Dgames=2 -Dseed=61000001 -Dthreads=2 -DcombatMinutes=120
```

## Development comparison

Development used hexadecimal master seed `61`, four alternating-spawn games per opponent,
and a 30 minute time limit. The first complete gauntlet and the selected implementation were:

| Version | Wins | Losses | Draws | Forfeits |
|---|---:|---:|---:|---:|
| Initial implementation | 10 | 3 | 3 | 0 |
| Selected implementation | 11 | 2 | 3 | 0 |

See [baseline.csv](benchmarks/baseline.csv) and [tuned.csv](benchmarks/tuned.csv).
This small tuning set was used for selection; it is separate from the fresh-seed evaluation.

Changes included terrain corridors and earlier climb commands for torpedoes, curved
navigation projections, recovery when stalled against terrain, sustained escape courses,
a 12 metre fuse, and four-weapon salvos. Alternatives with quieter seekers, delayed
submarine pings, smaller salvos, and earlier long evasions were also simulated. The selected
policy gave the best complete tuning gauntlet among those alternatives.

## Verification

- Six regression tests passed: API-only standalone compilation, intercept geometry,
  active/passive tracking precedence, guarded patrol across four procedural maps,
  launcher rejection, and malformed torpedo missions.
- Engine league preflight passed, including its bytecode rules scan and two guarded smoke matches.
- Production sources: five Java files, with only `searobots-api` and JDK dependencies.
- A controller JAR was packaged under `target/`; the service-provider entry names `Sol61Submarine`.

The Windows sandbox's path canonicalization restriction affected Maven's compiler.
Local verification compiled explicit source files with `javac`, then ran Maven Surefire
and the unmodified engine preflight. Normal development and CI use `mvn test` and `mvn -Pcheck`.

Raw run logs are retained under `target/final-*.log`. The PowerShell helper
`scripts/summarize-benchmarks.ps1` converts completed runner logs into the published CSV format.

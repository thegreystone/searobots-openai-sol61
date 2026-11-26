# Sol 6.1 for SeaRobots

An independent submarine and torpedo controller, built from the SeaRobots template's Maven,
CI, license, and service-registration structure. Requires Java 25 and Maven 3.9+.

Controller: `se.hirt.searobots.sub.sol61.Sol61Submarine`

Display name: **Sol 6.1** / sail code **S6**

SeaRobots API and simulation engine: **0.1.3**

```sh
mvn test
mvn -Pcheck
mvn -Pmatch33 -Dopponent=all -Dseed=20261126
mvn -Pwatch -Dopponent=gemini -Dseed=2c
```

The template's `watch`, `replay`, `match33`, `check`, and `manifest` profiles remain available.
Use `-Dgames=8 -Dthreads=4 -DcombatMinutes=30` for shorter tuning runs. `match33` saves loss
and draw replays under `target/replays/`; open one with
`mvn -Preplay -Dreplay=target/replays/<file>.srl`.

## Strategy

The submarine actively acquires contacts, filters ranged observations into bounded position
and velocity estimates, and predicts a moving intercept. Recent active fixes take precedence
over correlated, uncertain passive ranges. It fires salvos of up to four torpedoes, spaced
1.2 seconds apart, when the estimated powered intercept fits the torpedo's endurance, then
opens the range. Escape courses persist long enough to build speed. Ordered navigation
objectives are supported.

Navigation combines public API terrain corridors with curved course projections, depth
lookahead, hull clearance, boundary margins, and feedback for heading, pitch, ballast, and
speed. Escape courses use local trajectory checks. A stalled boat reverses and replans.

Each torpedo receives a finite, versioned launch message containing the target's estimated
motion and the launcher's last known state. After release it has no link to the submarine.
It associates its own sonar returns with the predicted hostile track, rejects likely launcher
returns, follows terrain corridors, leads moving contacts, and uses fin commands below stall.
Its 12 metre fuse balances hit probability and blast damage.
Lost contacts trigger a bounded search around the last predicted track.

## Clean-room boundary

The production controllers were written from scratch. The template controllers were not
copied or used as tactical implementations. Public API sources, engine physics, sonar,
match-running, and validation infrastructure were studied. No source or bytecode of controllers
under packages named `codex`, `claude`, or `gemini` was inspected. Those opponents were used
only as black boxes in simulations. `AGENTS.md` preserves this restriction for future work.

Production code has no dependencies beyond `searobots-api` and the JDK. The league can compile
`src/main/java` directly with `javac --release 25 -proc:none`.

## Development checks

Tests cover intercept geometry, active/passive tracking precedence, launcher rejection,
malformed torpedo missions, standalone API-only compilation, and guarded patrols across
four procedural maps. Tests and the optional `TraceMatch` tool alone use the simulation engine.
`TraceMatch` writes CSV diagnostics under `target/traces/`; enemy truth in those diagnostics
is available only to the offline observer and never to either controller.

See [BENCHMARKS.md](BENCHMARKS.md) for measured results and reproduction commands.

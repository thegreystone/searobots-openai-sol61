# Clean-room controller development

Do not read, search, decompile, or otherwise inspect any built-in controller source or bytecode
under a package segment named `codex`, `claude`, or `gemini`. Treat these opponents as black boxes.
Running them in headless simulations and examining public match telemetry is allowed.

The submarine and torpedo strategies in `src/main/java` are independent implementations.
Use `searobots-api` and standard Java 25 only in production sources. Engine code is available
to tests and development tools; it must not be imported by production sources.

Preserve deterministic behavior for fixed match inputs. Keep all per-match and per-torpedo
state in instances and reset it in the corresponding lifecycle callback.

Validate tactical changes with fixed-seed headless matches, alternating spawn slots, and
the league preflight. Report wins, losses, and draws separately. Keep evaluation seeds
separate from seeds used during tuning.

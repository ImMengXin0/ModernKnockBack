# First-hit probe (test-only)

Do **not** install this on a production server. It refuses to enable unless
`server-ip=127.0.0.1` and `server-port=27183`. It places a small stone platform,
teleports test players and provides a shutdown command to `KBQA*` test accounts.
Use a disposable Paper 1.21.11 server with only ModernKnockBack and this probe.
Do not expose the port or reuse production worlds/player data.

1. Build the plugin at the repository root using Java 21+: `mvn package`.
2. Build the probe: `mvn -f tools/first-hit-probe/pom.xml package`.
3. Copy the two jars into the disposable server's `plugins` folder. Set
   `online-mode=false`, `spawn-protection=0`, `view-distance=2`,
   `simulation-distance=2`, and the loopback address/port above.
4. Before starting, create `plugins/ModernKnockBack/mode.yml` with `mode: classic`.
5. After `Done`, run `npm install --no-save mineflayer@4.37.1` in this directory,
   then `node client.cjs plain` (or set `NODE_PATH` to an existing installation).
6. Read the six `FIRST_HIT_SAMPLE` lines. The client stops the disposable server
   after each run. Repeat in **fresh JVMs**, alternating baseline and candidate.

`plain` records just the duration of a real `Player.attack(target)` call with
two connected real players, plus damage and the velocity event. It executes the
server's normal PrePlayerAttack/damage/knockback/velocity event pipeline, but
does not measure network RTT, client rendering or a physical mouse click.
Attacks are 30 ticks apart; position, health and immunity are reset each time.

`node client.cjs instrument` additionally wraps registered listeners, preserving
priority, ordering and the original cancellation policy. This intentionally adds
measurement overhead; **do not compare these timings directly to plain runs**.
Nested handler durations can overlap. `node client.cjs stop` stops a leftover
probe server if a client test failed.

For first-use attribution, JFR `jdk.ClassLoad` events with stack traces can show
which classes load on the first attack versus during plugin enable. Class-load
recording is a separate diagnostic run, not the latency baseline. Core/Java
cold paths still exist even if all plugin-owned classes are initialized early.

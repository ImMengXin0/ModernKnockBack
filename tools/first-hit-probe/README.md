# First-hit probe (test-only)

Do **not** install this on a production server. It refuses to enable unless
`server-ip=127.0.0.1` and `server-port=27183`. It places a small stone platform,
teleports test players and provides a shutdown command to `KBQA*` test accounts.
Use a disposable Paper 1.21.11 server with only ModernKnockBack and this probe.
ModernKnockBack is a soft dependency so the same probe can run an absent-plugin control.
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

## Reconnect and network checks

`node client.cjs rejoin` runs three six-hit sequences in one JVM: initial login,
reconnect with the same accounts, then login with different accounts. It distinguishes
global cold initialization from a cost that repeats on every login.

`node client.cjs network` sends real client attack packets instead of calling
`Player.attack` on the server. `network-rejoin` combines it with the three login sequences.
The victim client records receipt of damage, hurt-animation and velocity packets,
timed from the attacker client's send call with the same process clock. These loopback
measurements include client processing and network/server scheduling; they are not
isolated plugin costs or a guarantee for remote clients.

The server logs `observedPreToVelocityMs` and `serverTickMs`. The pre-attack observer
runs after ModernKB's LOWEST handler, so that interval is **not** the full attack duration.
Paper may process attack packets between ticks; its tick-duration event does not
necessarily include packet processing. Do not equate the two fields.

Set `EXPECT_CLASSIC=1` when testing the default classic profile to also assert the
actual received velocity is `(0.4, 0.36075, 0)` within packet quantization. Keep this
unset for modern/absent-plugin controls or custom profiles. The local test decoder
uses the native 1.21.11 `LpVec3` layout (two bytes, then a big-endian int); the installed
Mineflayer dependency decodes that int as little-endian. No installed dependency is
patched. Check the decoder with `node velocity-packet.cjs`.

The probe resets position and health between samples and disables client physics.
It does not test rendered animations, human combat feel, sprint/jump combinations,
or interactions with production plugins.

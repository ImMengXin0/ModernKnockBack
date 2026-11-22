package cn.mx.modernkb.profiler;

import cn.mx.modernkb.Messages;
import cn.mx.modernkb.api.*;
import cn.mx.modernkb.combat.CombatTracker;
import cn.mx.modernkb.compat.SyncCompatibility;
import cn.mx.modernkb.compat.MotionWriteProbe;
import cn.mx.modernkb.config.*;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/** Read-only observations on the entity region; only immutable data reaches the exporter. */
public final class KnockbackProfiler implements Listener, MotionWriteProbe, AutoCloseable {
    public static final int MAX_SAMPLES = 10_000;
    public static final long MAX_SECONDS = 15 * 60;
    private static final int VELOCITY_EVENT_TICKS = 3;
    private static final int TRACE_TICKS = 6;
    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final PlayerStore players;
    private final CombatTracker tracker;
    private final Supplier<Map<String, Object>> counters;
    private final Supplier<Map<String, Object>> environment;
    private final ScheduledExecutorService writer;
    private final AtomicReference<Active> active = new AtomicReference<>();
    private final Object lifecycle = new Object();
    private volatile boolean exporting, closed, warned;
    private volatile Path lastReport;

    private static final class Active {
        final ProfilerRecording recording;
        final CommandSender initiator;
        final Map<String, Object> baseline;
        final ConcurrentMap<EntityKnockbackEvent, Pending> knockbacks = new ConcurrentHashMap<>();
        final ConcurrentMap<PlayerVelocityEvent, Velocity> velocities = new ConcurrentHashMap<>();
        final ConcurrentMap<UUID, Damage> damages = new ConcurrentHashMap<>();
        final ConcurrentMap<UUID, Pending> latest = new ConcurrentHashMap<>();
        Active(ProfilerRecording recording, CommandSender initiator, Map<String, Object> baseline) {
            this.recording = recording; this.initiator = initiator; this.baseline = baseline;
        }
        void clear() { knockbacks.clear(); velocities.clear(); latest.clear(); damages.clear(); }
    }
    private record Pending(ProfilerRecording.Ticket ticket, long nanoTime, int tick, UUID world,
                           double x, double y, double z, AtomicInteger velocityCount, AtomicLong firstVelocityNanos) {}
    private record Velocity(Pending hit, Vector original, long entered) {}

    // Netty can submit feedback on either side of the region thread's KB callback. Retain
    // at most one candidate per online victim. Never read the Bukkit event on Netty.
    private static final class Damage implements MotionWriteProbe.Feedback {
        final WeakReference<EntityDamageEvent> event;
        final int source, tick;
        final long monitor;
        final ProfilerRecording recording;
        volatile boolean observed;
        long feedback;
        Pending base, extra;
        boolean ambiguous;
        Damage(ProfilerRecording recording, EntityDamageEvent event, int source, int tick, long monitor) {
            this.recording = recording; this.event = new WeakReference<>(event); this.source = source; this.tick = tick; this.monitor = monitor;
        }
        @Override public synchronized void submitted(int attacker, long now) {
            if (observed || attacker != source) return;
            feedback = now; observed = true; publish();
        }
        synchronized void bind(Pending value) {
            // Modern melee can produce base and extra KB events for the same damage event.
            if (base == null) base = value;
            else if (extra == null) extra = value;
            else return;
            recording.calculated(value.ticket, Map.of("damageMonitorToKbMs", (value.nanoTime - monitor) / 1_000_000.0));
            if (observed) publish(value);
        }
        private void publish() {
            publish(base); publish(extra);
        }
        private void publish(Pending hit) {
            if (hit != null && !ambiguous) recording.calculated(hit.ticket, Map.of("feedbackFromKbMs", (feedback - hit.nanoTime) / 1_000_000.0));
        }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void damageFeedback(EntityDamageEvent event) {
        Active session = active.get();
        if (session == null) return;
        observe(() -> {
            long now = System.nanoTime();
            if (!(event.getEntity() instanceof Player victim) || !CombatTracker.owns(victim)) return;
            UUID id = victim.getUniqueId();
            if (!event.isCancelled() && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    && event instanceof EntityDamageByEntityEvent melee && melee.getDamager() instanceof Player source
                    && CombatTracker.owns(source) && settings.get().mode() == CombatMode.MODERN && !players.get(id).excluded()) {
                Damage next = new Damage(session.recording, event, source.getEntityId(), victim.getTicksLived(), now);
                Damage last = session.damages.get(id);
                next.ambiguous = last != null && last.source == next.source && !last.observed
                        && next.tick >= last.tick && next.tick - last.tick <= VELOCITY_EVENT_TICKS;
                session.damages.put(id, next);
            } else session.damages.remove(id);
        });
    }
    @Override public MotionWriteProbe.Feedback feedbackTarget(UUID id) {
        Active session = active.get();
        Damage damage = session == null ? null : session.damages.get(id);
        return damage == null || damage.observed ? null : damage;
    }

    public KnockbackProfiler(Plugin plugin, Supplier<Settings> settings, PlayerStore players,
                             CombatTracker tracker, Supplier<Map<String, Object>> counters,
                             Supplier<String> syncStatus) {
        this(plugin, settings, players, tracker, counters,
                () -> serverEnvironment(plugin, syncStatus.get()), Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread thread = new Thread(r, "ModernKnockBack-profiler");
                    thread.setDaemon(true); return thread;
                }), true);
    }

    KnockbackProfiler(Plugin plugin, Supplier<Settings> settings, PlayerStore players,
                      CombatTracker tracker, Supplier<Map<String, Object>> counters,
                      Supplier<Map<String, Object>> environment, ScheduledExecutorService writer, boolean watchdog) {
        this.plugin = plugin; this.settings = settings; this.players = players; this.tracker = tracker;
        this.counters = counters; this.environment = environment; this.writer = writer;
        if (watchdog) writer.scheduleWithFixedDelay(this::checkLimit, 1, 1, TimeUnit.SECONDS);
    }

    public void command(CommandSender sender, String operation) {
        switch (operation.toLowerCase(Locale.ROOT)) {
            case "open" -> {
                synchronized (lifecycle) {
                    if (closed) { Messages.err(sender, "Profiler 已关闭"); return; }
                    if (active.get() != null) { Messages.tip(sender, "KB 检测已在进行，使用 /kbtest profiler stop 生成报告"); return; }
                    if (exporting) { Messages.tip(sender, "正在生成上一份报告，请稍候"); return; }
                    Settings config = settings.get();
                    String id = Instant.now().toString().replaceAll("[^0-9]", "") + "-" + UUID.randomUUID().toString().substring(0, 8);
                    var recording = new ProfilerRecording(id, sender.getName(), config.mode().key(), environmentSnapshot(), MAX_SAMPLES, MAX_SECONDS);
                    recording.configuration(config); // Do not first flatten every profile inside a combat event.
                    active.set(new Active(recording, sender, countersSnapshot()));
                    Messages.ok(sender, "KB 检测已开启 §8| §7当前模式 §b" + config.mode().key());
                    Messages.note(sender, "使用 /kbtest profiler stop 导出离线 HTML；上限 15 分钟 / 10000 条事件");
                }
            }
            case "stop" -> {
                if (!stop(sender, "command")) Messages.tip(sender, exporting ? "正在生成报告，请稍候" : "没有正在进行的 KB 检测");
            }
            case "status" -> {
                Active session = active.get();
                if (session == null) Messages.note(sender, exporting ? "Profiler 正在生成报告" : "Profiler 未开启");
                else Messages.tip(sender, "Profiler 已记录 §f" + session.recording.size() + " §7条事件 §8| §7当前模式 §b" + settings.get().mode().key());
            }
            case "report" -> {
                if (lastReport == null) Messages.tip(sender, "还没有生成报告，先使用 /kbtest profiler open");
                else showReport(sender, lastReport);
            }
            default -> Messages.tip(sender, "用法 /kbtest profiler open|stop|status|report");
        }
    }

    /** Called after atomic config publication, including reload, API mode switches and GUI edits. */
    public void configurationChanged(Settings config) {
        Active session = active.get();
        if (session != null) observe(() -> session.recording.configuration(config));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void begin(EntityKnockbackEvent event) {
        Active session = active.get();
        if (session == null) return;
        observe(() -> begin(session, event));
    }
    private void begin(Active session, EntityKnockbackEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim) || !CombatTracker.owns(victim)) return;
        Settings config = settings.get();
        CombatantKind kind = tracker.kind(victim);
        if (!config.affectOthers() && kind != CombatantKind.PLAYER && kind != CombatantKind.BOT) return;
        if (players.get(victim.getUniqueId()).excluded()) return;
        long started = System.nanoTime();
        Entity source = event instanceof EntityPushedByEntityAttackEvent attack ? attack.getPushedBy() : null;
        // Merely holding a source from an event grants no permission to read it on another region.
        boolean ownsSource = CombatTracker.owns(source);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("mode", config.mode().key()); fields.put("effectiveMode", config.mode().key());
        fields.put("configuration", session.recording.configuration(config));
        fields.put("profile", "unhandled"); fields.put("type", "unknown"); fields.put("cause", event.getCause().name());
        fields.put("victim", victim instanceof Player p ? p.getName() : victim.getType().name());
        fields.put("victimId", victim.getUniqueId().toString());
        fields.put("attacker", ownsSource && source instanceof Player p ? p.getName() : "unknown");
        fields.put("victimPing", victim instanceof Player p ? ping(p) : -1);
        fields.put("attackerPing", ownsSource && source instanceof Player p ? ping(p) : -1);
        fields.put("grounded", victim.isOnGround()); fields.put("sprinting", false); fields.put("critical", false);
        fields.put("charge", null); fields.put("syncOwnsVertical", false); fields.put("weapon", "");
        fields.put("original", vector(event.getKnockback())); fields.put("before", vector(victim.getVelocity()));
        fields.put("calculated", null); fields.put("applied", null); fields.put("nativeApplied", false);
        fields.put("fallback", "not-processed"); fields.put("processed", false);
        fields.put("velocity", null); fields.put("movement", null);
        ProfilerRecording.Ticket ticket = session.recording.begin(fields);
        if (ticket == null) return;
        Location location = victim.getLocation();
        session.knockbacks.put(event, new Pending(ticket, started, victim.getTicksLived(), location.getWorld().getUID(),
                location.getX(), location.getY(), location.getZ(), new AtomicInteger(), new AtomicLong()));
    }

    /** The calculation path supplies immutable attribution; it never causes extra combat events. */
    public void context(EntityKnockbackEvent event, HitContext context, Settings configured, Settings combat, String profile) {
        Active session = active.get();
        if (session == null) return;
        observe(() -> {
            Pending hit = session.knockbacks.get(event);
            if (hit == null) return;
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("mode", configured.mode().key());
            fields.put("configuration", session.recording.configuration(configured));
            fields.put("effectiveMode", combat.mode().key()); fields.put("profile", profile);
            fields.put("type", context.type().key()); fields.put("grounded", context.grounded());
            fields.put("sprinting", context.sprinting()); fields.put("critical", context.critical());
            fields.put("charge", context.attackCharge()); fields.put("syncOwnsVertical", context.syncOwnsVertical());
            fields.put("weapon", context.weapon());
            if (context.attacker() != null) {
                fields.put("attackerId", context.attacker().toString());
                // Rod/public knockback calls can lack a native source. Resolve only a player reference,
                // then check region ownership before reading the actual player state.
                Player attacker = Bukkit.getPlayer(context.attacker());
                if (CombatTracker.owns(attacker)) {
                    fields.put("attacker", attacker.getName()); fields.put("attackerPing", ping(attacker));
                }
            }
            session.recording.calculated(hit.ticket, fields);
        });
    }
    public void calculation(EntityKnockbackEvent event, Vector calculated, boolean nativeApplied, String fallback) {
        Active session = active.get();
        if (session == null) return;
        observe(() -> {
            Pending hit = session.knockbacks.get(event);
            if (hit != null) session.recording.calculated(hit.ticket, Map.of("calculated", vector(calculated),
                    "nativeApplied", nativeApplied, "fallback", fallback));
        });
    }
    public void applied(EntityKnockbackEvent event, Vector applied) {
        Active session = active.get();
        if (session == null) return;
        observe(() -> {
            Pending hit = session.knockbacks.get(event);
            if (hit != null) session.recording.calculated(hit.ticket, Map.of("applied", vector(applied), "processed", true));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finish(EntityKnockbackEvent event) {
        Active session = active.get();
        if (session == null) return;
        observe(() -> {
            Pending hit = session.knockbacks.remove(event);
            if (hit == null) return;
            session.recording.finish(hit.ticket, Map.of("final", vector(event.getKnockback()), "cancelled", event.isCancelled(),
                    "processingMicros", (System.nanoTime() - hit.nanoTime) / 1_000.0));
            if (event.isCancelled() || !(event.getEntity() instanceof LivingEntity victim) || !CombatTracker.owns(victim)) return;
            UUID id = victim.getUniqueId();
            Damage damage = session.damages.get(id);
            EntityDamageEvent cause = damage == null ? null : damage.event.get();
            if (cause != null && !cause.isCancelled() && damage.tick == hit.tick && event.getCause() == EntityKnockbackEvent.Cause.ENTITY_ATTACK
                    && event instanceof EntityPushedByEntityAttackEvent attack && attack.getPushedBy() instanceof Player source
                    && CombatTracker.owns(source) && source.getEntityId() == damage.source
                    && cause == victim.getLastDamageCause()) damage.bind(hit);
            session.recording.startTrace(hit.ticket, victim instanceof Player);
            Pending previous = session.latest.put(id, hit);
            if (previous != null) session.recording.endTrace(previous.ticket, "new-hit", hit.tick - previous.tick);
            schedulePoint(session, hit, victim, id, 0, 0);
        });
    }

    // Netty thread: capture the specific ticket at write time, never look up the player on completion.
    @Override public MotionWriteProbe.Capture motionTarget(UUID recipient) {
        Active session = active.get();
        Pending hit = session == null ? null : session.latest.get(recipient);
        if (hit == null) return null;
        return (now, x, y, z, bundled) -> session.recording.submitPacket(hit.ticket, hit.nanoTime, now, x, y, z, bundled);
    }

    private void schedulePoint(Active session, Pending hit, LivingEntity victim, UUID id, int lastAge, int attempts) {
        Runnable retired = () -> {
            if (session.latest.remove(id, hit)) session.recording.endTrace(hit.ticket, "retired", lastAge);
        };
        var scheduled = victim.getScheduler().runDelayed(plugin, task -> observe(() -> {
            if (active.get() != session || session.latest.get(id) != hit) return;
            if (!CombatTracker.owns(victim)) { retired.run(); return; }
            int age = victim.getTicksLived() - hit.tick;
            Location now = victim.getLocation();
            String end = null;
            if (victim.isDead()) end = "death";
            else if (!now.getWorld().getUID().equals(hit.world)) end = "world-change";
            else if (age < 0 || age > TRACE_TICKS) end = "sampling-gap";
            else if (age > lastAge) {
                double dx = now.getX() - hit.x, dy = now.getY() - hit.y, dz = now.getZ() - hit.z;
                session.recording.point(hit.ticket, age, dx, dy, dz);
                // Keep the short-window summary for comparison with older reports; always use real entity age.
                if (lastAge < 2 && age >= 2) session.recording.movement(hit.ticket, Map.of("movement", Map.of(
                        "ticks", age, "interrupted", false, "dx", dx, "dy", dy, "dz", dz, "velocity", vector(victim.getVelocity()))));
                if (age == TRACE_TICKS) end = "complete";
            }
            // An entity scheduler can first run before the entity age advances. Never invent tick 1 or a missing point.
            if (end == null && attempts >= TRACE_TICKS + 2) end = "sampling-gap";
            if (end != null) {
                if (session.latest.remove(id, hit)) session.recording.endTrace(hit.ticket, end, age);
            } else schedulePoint(session, hit, victim, id, Math.max(age, lastAge), attempts + 1);
        }), retired, 1L);
        if (scheduled == null) retired.run(); // Already retired: no callback will be scheduled.
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) { invalidateMovement(event.getPlayer(), "teleport"); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void respawn(PlayerRespawnEvent event) { invalidateMovement(event.getPlayer(), "respawn"); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) { invalidateMovement(event.getPlayer(), "quit"); }
    private void invalidateMovement(Player player, String reason) {
        Active session = active.get();
        if (session == null || !CombatTracker.owns(player)) return;
        observe(() -> {
            session.damages.remove(player.getUniqueId());
            Pending hit = session.latest.remove(player.getUniqueId());
            if (hit == null) return;
            session.recording.endTrace(hit.ticket, reason, player.getTicksLived() - hit.tick);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void beginVelocity(PlayerVelocityEvent event) {
        Active session = active.get();
        if (session == null) return;
        observe(() -> {
            long entered = System.nanoTime();
            Player victim = event.getPlayer();
            if (!CombatTracker.owns(victim)) return;
            Pending hit = session.latest.get(victim.getUniqueId());
            int age = hit == null ? -1 : victim.getTicksLived() - hit.tick;
            if (hit != null && age >= 0 && age <= VELOCITY_EVENT_TICKS)
                session.velocities.put(event, new Velocity(hit, event.getVelocity().clone(), entered));
        });
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void finishVelocity(PlayerVelocityEvent event) {
        Active session = active.get();
        if (session == null) return;
        observe(() -> {
            Velocity observation = session.velocities.remove(event);
            if (observation == null) return;
            long now = System.nanoTime();
            int count = observation.hit.velocityCount.incrementAndGet();
            observation.hit.firstVelocityNanos.compareAndSet(0, now);
            var value = Map.<String, Object>of("before", vector(observation.original), "after", vector(event.getVelocity()),
                    "cancelled", event.isCancelled(), "delayMs", (now - observation.hit.nanoTime) / 1_000_000.0,
                    "firstDelayMs", (observation.hit.firstVelocityNanos.get() - observation.hit.nanoTime) / 1_000_000.0,
                    "entryDelayMs", (observation.entered - observation.hit.nanoTime) / 1_000_000.0,
                    "listenerMs", (now - observation.entered) / 1_000_000.0,
                    "count", count, "correlation", "same-victim-time-window");
            // Some cores dispatch twice during one hit. This is the latest observed event, not a packet ACK.
            session.recording.velocity(observation.hit.ticket, Map.of("velocity", value));
        });
    }

    private void checkLimit() {
        try {
            Active session = active.get();
            if (session != null && (session.recording.expired() || session.recording.size() >= MAX_SAMPLES))
                stop(session.initiator, session.recording.expired() ? "duration-limit" : "sample-limit", session);
        } catch (RuntimeException ex) { plugin.getLogger().log(Level.WARNING, "Profiler limit check failed", ex); }
    }
    private boolean stop(CommandSender recipient, String reason) {
        return stop(recipient, reason, null);
    }
    private boolean stop(CommandSender recipient, String reason, Active expected) {
        synchronized (lifecycle) {
            Active session = active.get();
            if (session == null || expected != null && expected != session) return false;
            session.recording.seal();
            active.set(null);
            exporting = true;
            Map<String, Object> end = environmentSnapshot();
            Map<String, Object> totals = differences(session.baseline, countersSnapshot());
            session.clear();
            reply(recipient, () -> Messages.tip(recipient, "检测已停止，正在生成 KB 图表与报告"));
            submit(() -> freezeAndExport(session, recipient, reason, end, totals));
            return true;
        }
    }
    private void freezeAndExport(Active session, CommandSender recipient, String reason,
                                 Map<String, Object> end, Map<String, Object> totals) {
        try { export(recipient, session.recording.stop(reason, end, totals)); }
        catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not freeze KB profiler data", ex);
            reply(recipient, () -> Messages.err(recipient, "报告数据生成失败，请检查服务器日志"));
        } finally { exporting = false; }
    }
    private void submit(Runnable export) {
        try { writer.execute(export); }
        catch (RejectedExecutionException ex) {
            plugin.getLogger().warning("Profiler worker unavailable; saving the report before shutdown");
            export.run();
        }
    }
    private Map<String, Object> environmentSnapshot() {
        try { return new LinkedHashMap<>(environment.get()); }
        catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not capture profiler environment", ex);
            return Map.of("snapshotError", ex.getClass().getSimpleName());
        }
    }
    private Map<String, Object> countersSnapshot() {
        try { return new LinkedHashMap<>(counters.get()); }
        catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not capture profiler counters", ex);
            return Map.of();
        }
    }
    private void export(CommandSender recipient, Map<String, Object> report) {
        try {
            Path path = ProfilerReportWriter.write(plugin.getDataFolder().toPath().resolve("profiler"), report);
            lastReport = path;
            plugin.getLogger().info("KB profiler report: " + path + " (" + path.toUri().toASCIIString() + ")");
            reply(recipient, () -> showReport(recipient, path));
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not write KB profiler HTML", ex);
            // Retain the frozen measurements if the viewer resource or HTML generation fails.
            try {
                Path recovery = ProfilerReportWriter.save(plugin.getDataFolder().toPath().resolve("profiler"),
                        "kb-" + report.get("id") + ".json", ProfilerJson.write(report));
                plugin.getLogger().warning("Profiler measurements preserved: " + recovery);
                reply(recipient, () -> Messages.err(recipient, "HTML 生成失败，原始数据已保存 §7" + recovery));
            } catch (Exception recoveryFailure) {
                plugin.getLogger().log(Level.SEVERE, "Could not preserve profiler measurements", recoveryFailure);
                reply(recipient, () -> Messages.err(recipient, "报告写入失败，请检查 profiler 目录权限和服务器日志"));
            }
        }
    }
    private void showReport(CommandSender sender, Path report) {
        Messages.ok(sender, "KB 报告已生成 §7" + report);
        sender.sendMessage(Component.text("[KB] 复制 HTML 文件链接")
                .clickEvent(ClickEvent.copyToClipboard(report.toUri().toASCIIString())));
        Messages.note(sender, "这是服务器本机文件链接；远程管理请下载该 HTML 后用浏览器打开");
        Messages.note(sender, "服务端事件时间与 Ping 不等于客户端收包时间或丢包率");
    }
    private void reply(CommandSender recipient, Runnable response) {
        if (closed) return;
        try {
            if (recipient instanceof Entity entity) entity.getScheduler().run(plugin, task -> response.run(), null);
            else Bukkit.getGlobalRegionScheduler().run(plugin, task -> response.run());
        } catch (RuntimeException ex) { plugin.getLogger().fine("Profiler recipient is unavailable: " + ex.getMessage()); }
    }
    private void observe(Runnable operation) {
        try { operation.run(); }
        catch (RuntimeException ex) {
            if (!warned) {
                warned = true;
                plugin.getLogger().log(Level.WARNING, "Profiler observation failed; combat was left untouched", ex);
            }
        }
    }
    private static int ping(Player player) { return Math.max(-1, player.getPing()); }
    static List<Double> vector(Vector vector) {
        return vector == null ? Arrays.asList(null, null, null) : Arrays.asList(vector.getX(), vector.getY(), vector.getZ());
    }
    private static Map<String, Object> differences(Map<String, Object> baseline, Map<String, Object> current) {
        Map<String, Object> result = new LinkedHashMap<>();
        current.forEach((key, value) -> result.put(key, value instanceof Number n && baseline.get(key) instanceof Number before
                ? Math.max(0, n.longValue() - before.longValue()) : value));
        return result;
    }
    static Map<String, Object> serverEnvironment(Plugin plugin, String syncStatus) {
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("server", Bukkit.getVersion()); environment.put("minecraft", Bukkit.getMinecraftVersion());
        environment.put("pluginVersion", plugin.getPluginMeta().getVersion()); environment.put("java", System.getProperty("java.version"));
        environment.put("regionized", SyncCompatibility.foliaRuntime()); environment.put("syncStatus", syncStatus);
        List<Map<String, Object>> installed = new ArrayList<>();
        for (Plugin entry : Bukkit.getPluginManager().getPlugins()) installed.add(Map.of("name", entry.getName(),
                "version", entry.getPluginMeta().getVersion(), "enabled", entry.isEnabled()));
        installed.sort(Comparator.comparing(p -> (String) p.get("name"), String.CASE_INSENSITIVE_ORDER));
        environment.put("plugins", installed);
        environment.put("eventHandlers", Map.of("knockback", handlers(EntityKnockbackEvent.getHandlerList()),
                "velocity", handlers(PlayerVelocityEvent.getHandlerList()), "damage", handlers(EntityDamageByEntityEvent.getHandlerList())));
        return environment;
    }
    private static List<String> handlers(HandlerList handlers) {
        return Arrays.stream(handlers.getRegisteredListeners()).map(handler -> handler.getPlugin().getName() + " / "
                + handler.getPriority().name() + (handler.isIgnoringCancelled() ? " / ignore-cancelled" : "")).distinct().toList();
    }
    @Override public void close() {
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            Active session = active.getAndSet(null);
            try {
                if (session != null) {
                    session.recording.seal();
                    Map<String, Object> end = environmentSnapshot();
                    Map<String, Object> totals = differences(session.baseline, countersSnapshot());
                    session.clear(); exporting = true;
                    submit(() -> freezeAndExport(session, session.initiator, "plugin-disable", end, totals));
                }
            } finally { writer.shutdown(); }
        }
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) plugin.getLogger().warning("Profiler export is still finishing in the background");
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
}

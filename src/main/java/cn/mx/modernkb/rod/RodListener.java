package cn.mx.modernkb.rod;

import cn.mx.modernkb.combat.CombatTracker;
import cn.mx.modernkb.combat.ClassicCombat;
import cn.mx.modernkb.api.HitType;
import cn.mx.modernkb.config.*;
import cn.mx.modernkb.compat.RodCollision;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.plugin.Plugin;

public final class RodListener implements Listener {
    private record Times(Integer cast, Integer reel) {}
    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final PlayerStore players;
    private final CombatTracker tracker;
    private final ClassicCombat classic;
    private final RodCollision collision;
    private final Map<UUID, Times> times = new ConcurrentHashMap<>();
    private final Set<ScheduledTask> tasks = ConcurrentHashMap.newKeySet();
    private final Set<FishHook> hooks = ConcurrentHashMap.newKeySet();
    public RodListener(Plugin plugin, Supplier<Settings> settings, PlayerStore players, CombatTracker tracker) {
        this(plugin, settings, players, tracker, null);
    }
    public RodListener(Plugin plugin, Supplier<Settings> settings, PlayerStore players, CombatTracker tracker, ClassicCombat classic) {
        this(plugin, settings, players, tracker, classic, RodCollision.nativeOnly());
    }
    public RodListener(Plugin plugin, Supplier<Settings> settings, PlayerStore players, CombatTracker tracker,
                       ClassicCombat classic, RodCollision collision) {
        this.plugin = plugin; this.settings = settings; this.players = players; this.tracker = tracker;
        this.classic = classic; this.collision = collision;
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void impact(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof FishHook hook) || !(event.getHitEntity() instanceof LivingEntity victim)
                || !CombatTracker.owns(hook)) return;
        if (!(hook.getShooter() instanceof Player player) || !CombatTracker.owns(player)) return;
        var rod = config(player);
        if (rod == null) return;
        // Own the classic collision: vanilla may attach even after protection rejects the damage.
        boolean legacy = settings.get().classicEnabled();
        boolean cancelled = event.isCancelled();
        if (legacy) event.setCancelled(true);
        try {
            if (!cancelled && (!legacy || !ClassicRodContact.spent(hook))) applyImpact(hook, player, victim, rod);
        } finally {
            if (legacy) {
                ClassicRodContact.finish(hook);
                if (hook.isValid()) hooks.add(hook);
            }
        }
    }
    private void applyImpact(FishHook hook, Player player, LivingEntity victim, KnockbackProfile.Rod rod) {
        if (!CombatTracker.owns(victim) || rod.impactStrength() <= 0 || players.get(victim.getUniqueId()).excluded()) return;
        var kind = tracker.kind(victim);
        if (!settings.get().affectOthers() && kind != cn.mx.modernkb.api.CombatantKind.PLAYER && kind != cn.mx.modernkb.api.CombatantKind.BOT) return;
        var direction = hook.getVelocity();
        if (direction.getX() * direction.getX() + direction.getZ() * direction.getZ() < 1.0e-12) return;
        var before = victim.getVelocity();
        if (settings.get().classicEnabled()) {
            if (classic == null) return;
            // ClassicCombat completes velocity delivery before the cosmetic hurt effects.
            classic.rodImpactResult(hook, player, victim, rod.impactStrength());
            return;
        } else
            tracker.withType(player, victim, HitType.FISHING_ROD,
                    () -> victim.knockback(rod.impactStrength(), -direction.getX(), -direction.getZ()));
        // Modern collision KB needs a dirty velocity only when native KB changed it.
        var after = victim.getVelocity();
        if (!after.equals(before)) victim.setVelocity(after);
    }
    private KnockbackProfile.Rod config(Player player) {
        Settings config = settings.get();
        var preference = players.get(player.getUniqueId());
        if (!config.enabled() || preference.excluded()) return null;
        var profile = config.profiles().get(config.rodProfile(preference.profile()));
        return profile.enabled() && profile.rod().enabled() ? profile.rod() : null;
    }
    public static boolean blocked(Integer previous, int now, int interval) {
        return previous != null && interval > 0 && Integer.toUnsignedLong(now - previous) < interval;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void preventClassicPull(PlayerFishEvent event) {
        if (event.isCancelled() || event.getState() != PlayerFishEvent.State.CAUGHT_ENTITY
                || !(event.getCaught() instanceof LivingEntity) || !settings.get().classicEnabled()
                || !CombatTracker.owns(event.getPlayer()) || !CombatTracker.owns(event.getHook())
                || config(event.getPlayer()) == null) return;
        // Cancellation happens before native pullEntity and before the client pull status packet (31).
        event.setCancelled(true);
        FishHook hook = event.getHook();
        hook.setHookedEntity(null);
        hook.remove(); hooks.remove(hook);
        recordReel(event.getPlayer());
    }
    private void recordReel(Player player) {
        Times old = times.get(player.getUniqueId());
        times.put(player.getUniqueId(), new Times(old == null ? null : old.cast, player.getTicksLived()));
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void limit(PlayerFishEvent event) {
        Player player = event.getPlayer();
        if (event.getState() != PlayerFishEvent.State.FISHING || !CombatTracker.owns(player)) return;
        var rod = config(player);
        if (rod == null) return;
        Times previous = times.get(player.getUniqueId());
        int now = player.getTicksLived();
        if (previous != null && (blocked(previous.cast, now, rod.castTicks()) || blocked(previous.reel, now, rod.recastTicks())))
            event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void accepted(PlayerFishEvent event) {
        Player player = event.getPlayer();
        if (!CombatTracker.owns(player)) return;
        var rod = config(player);
        if (rod == null) return;
        UUID id = player.getUniqueId();
        int now = player.getTicksLived();
        if (event.getState() == PlayerFishEvent.State.FISHING) {
            Times old = times.get(id);
            times.put(id, new Times(now, old == null ? null : old.reel));
            FishHook hook = event.getHook();
            Settings snapshot = settings.get();
            hooks.add(hook);
            if (!snapshot.classicEnabled() && rod.horizontalSpeed() == 1 && rod.verticalSpeed() == 1 && rod.maxDistance() == 0 && rod.lifetimeTicks() == 0) return;
            // The hook is not yet valid/spawned here. Ownership, not validity, allows safe launch tuning.
            boolean launched = CombatTracker.owns(hook);
            if (launched) launch(hook, rod);
            hook.getScheduler().run(plugin, task -> {
                if (hook.isValid() && plugin.isEnabled() && settings.get() != snapshot) { hook.remove(); hooks.remove(hook); }
                else if (!event.isCancelled() && hook.isValid() && plugin.isEnabled() && snapshot.enabled()) {
                    if (!launched) launch(hook, rod);
                    sweep(hook, snapshot);
                    if (!hook.isValid()) return;
                    start(hook, rod, snapshot);
                }
            }, null);
        } else if (isReel(event.getState())) {
            recordReel(player);
        }
    }
    public static boolean isReel(PlayerFishEvent.State state) {
        return state == PlayerFishEvent.State.REEL_IN || state == PlayerFishEvent.State.IN_GROUND
                || state == PlayerFishEvent.State.CAUGHT_ENTITY || state == PlayerFishEvent.State.CAUGHT_FISH;
    }
    private void launch(FishHook hook, KnockbackProfile.Rod rod) {
        var velocity = hook.getVelocity();
        if (rod.horizontalSpeed() != 1 || rod.verticalSpeed() != 1) {
            velocity.setX(velocity.getX() * rod.horizontalSpeed()).setZ(velocity.getZ() * rod.horizontalSpeed())
                    .setY(velocity.getY() * rod.verticalSpeed());
            hook.setVelocity(velocity);
        }
    }
    private void start(FishHook hook, KnockbackProfile.Rod rod, Settings snapshot) {
        if (!snapshot.classicEnabled() && rod.maxDistance() == 0 && rod.lifetimeTicks() == 0) return;
        var origin = hook.getLocation();
        int born = hook.getTicksLived();
        ScheduledTask[] holder = new ScheduledTask[1];
        ScheduledTask task = hook.getScheduler().runAtFixedRate(plugin, scheduled -> {
            if (!plugin.isEnabled() || settings.get() != snapshot || !snapshot.enabled() || !hook.isValid()) {
                scheduled.cancel(); tasks.remove(scheduled); hooks.remove(hook); return;
            }
            var location = hook.getLocation();
            boolean expired = rod.lifetimeTicks() > 0 && Integer.toUnsignedLong(hook.getTicksLived() - born) >= rod.lifetimeTicks();
            boolean outside = rod.maxDistance() > 0 && (!origin.getWorld().equals(location.getWorld())
                    || origin.distanceSquared(location) > rod.maxDistance() * rod.maxDistance());
            if (expired || outside) { hook.remove(); scheduled.cancel(); tasks.remove(scheduled); hooks.remove(hook); return; }
            sweep(hook, snapshot);
            if (!hook.isValid()) { scheduled.cancel(); tasks.remove(scheduled); hooks.remove(hook); }
        }, () -> { if (holder[0] != null) tasks.remove(holder[0]); hooks.remove(hook); }, 1, 1);
        holder[0] = task;
        if (task != null && !task.isCancelled()) tasks.add(task);
    }
    private void sweep(FishHook hook, Settings snapshot) {
        if (!snapshot.classicEnabled() || !CombatTracker.owns(hook) || !hook.isValid()
                || ClassicRodContact.spent(hook)
                || hook.getState() != FishHook.HookState.UNHOOKED || hook.getHookedEntity() != null
                || hook.isOnGround() || hook.isInWater()
                || !(hook.getShooter() instanceof Player player) || !CombatTracker.owns(player)) return;
        var rod = config(player);
        if (rod == null || rod.impactStrength() <= 0) return;
        LivingEntity victim = collision.trace(hook);
        if (victim == null || victim == player || !CombatTracker.owns(victim)) return;
        // Dispatch through the usual protection listeners and the one existing impact pipeline.
        // Even a cancelled contact spends this hook; it stays visible but cannot retry.
        try {
            org.bukkit.Bukkit.getPluginManager().callEvent(new ProjectileHitEvent(hook, victim));
        } finally {
            ClassicRodContact.finish(hook);
            if (hook.isValid()) hooks.add(hook);
        }
    }
    @EventHandler public void quit(PlayerQuitEvent event) { times.remove(event.getPlayer().getUniqueId()); }
    @EventHandler public void respawn(PlayerRespawnEvent event) { times.remove(event.getPlayer().getUniqueId()); }
    @EventHandler public void removed(org.bukkit.event.entity.EntityRemoveEvent event) {
        if (event.getEntity() instanceof FishHook hook) hooks.remove(hook);
    }
    public void reset() {
        tasks.forEach(ScheduledTask::cancel); tasks.clear(); times.clear();
        for (FishHook hook : new ArrayList<>(hooks)) hook.getScheduler().run(plugin, task -> { if (hook.isValid()) hook.remove(); }, null);
        hooks.clear();
    }
    public void close() { tasks.forEach(ScheduledTask::cancel); tasks.clear(); times.clear(); hooks.clear(); }
}

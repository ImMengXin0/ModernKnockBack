package cn.mx.modernkb.combat;

import cn.mx.modernkb.api.*;
import cn.mx.modernkb.config.*;
import cn.mx.modernkb.compat.RodVelocity;
import cn.mx.modernkb.compat.RodFeedback;
import cn.mx.modernkb.compat.LeafVelocityDispatch;
import cn.mx.modernkb.rod.ClassicRodContact;
import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/** Owns classic timing and per-hit deduplication, never queues an attack or a velocity. */
public final class ClassicCombat implements Listener {
    private static final double ATTACK_SPEED_BONUS = 1024;
    private static final class Hit {
        final Settings settings;
        final int tick;
        final EntityDamageByEntityEvent damage;
        final ClassicKnockbackMath.Attack attack;
        boolean applied;
        EntityKnockbackEvent knockback;
        Hit(Settings settings, int tick, EntityDamageByEntityEvent damage, ClassicKnockbackMath.Attack attack) {
            this.settings = settings; this.tick = tick; this.damage = damage; this.attack = attack;
        }
    }
    private record Ground(double y, boolean limited) {}
    private record RodStamp(Settings settings, int tick, EntityDamageByEntityEvent damage, EntityKnockbackEvent knockback) {}
    private record Pending(Settings settings, int tick, EntityDamageByEntityEvent damage,
                           EntityKnockbackEvent knockback, boolean rod, boolean leafFlush, Object generation) {}
    private record VelocityGuard(Pending token, Vector velocity, boolean leafFlushFollows) {}
    private static final class RodScope {
        final Settings settings;
        final FishHook hook;
        final LivingEntity victim;
        final UUID victimId;
        final ClassicKnockbackMath.Attack attack;
        EntityDamageByEntityEvent damage;
        EntityKnockbackEvent knockback;
        boolean damaging = true;
        boolean applied;
        boolean accepted;
        int immunityWindow = -1;
        RodScope(Settings settings, FishHook hook, LivingEntity victim, ClassicKnockbackMath.Attack attack) {
            this.settings = settings; this.hook = hook; this.victim = victim; this.attack = attack;
            this.victimId = victim.getUniqueId();
        }
    }
    private record Prepared(Settings settings, HitContext context, Calculation calculation) {}
    public record Calculation(Vector impulse, Runnable commit) {}
    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final PlayerStore players;
    private final CombatTracker tracker;
    private final RodVelocity rodVelocity;
    private final RodFeedback rodFeedback;
    private final NamespacedKey speedKey, savedImmunityKey, appliedImmunityKey;
    private final Map<UUID, LivingEntity> managed = new ConcurrentHashMap<>();
    private final Map<UUID, Hit> hits = new ConcurrentHashMap<>();
    private final Map<UUID, Ground> grounds = new ConcurrentHashMap<>();
    private final ClassicJumpTracker jumps = new ClassicJumpTracker();
    private final Map<UUID, RodStamp> rodHits = new ConcurrentHashMap<>();
    private final Map<UUID, RodScope> activeRods = new ConcurrentHashMap<>();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<PlayerVelocityEvent, VelocityGuard> velocityEvents = new ConcurrentHashMap<>();
    private final Map<EntityKnockbackEvent, Prepared> prepared = new ConcurrentHashMap<>();
    private final ThreadLocal<RodScope> rodScope = new ThreadLocal<>();
    private final LongAdder preventedHits = new LongAdder(), preventedComponents = new LongAdder(), applied = new LongAdder();
    private final LongAdder rodContacts = new LongAdder(), rodAccepted = new LongAdder(), rodFeedbacks = new LongAdder();

    public ClassicCombat(Plugin plugin, Supplier<Settings> settings, PlayerStore players, CombatTracker tracker) {
        this(plugin, settings, players, tracker, RodVelocity.deferred());
    }
    public ClassicCombat(Plugin plugin, Supplier<Settings> settings, PlayerStore players, CombatTracker tracker, RodVelocity rodVelocity) {
        this(plugin, settings, players, tracker, rodVelocity, RodFeedback.bukkit());
    }
    public ClassicCombat(Plugin plugin, Supplier<Settings> settings, PlayerStore players, CombatTracker tracker,
                         RodVelocity rodVelocity, RodFeedback rodFeedback) {
        this.plugin = plugin; this.settings = settings; this.players = players; this.tracker = tracker;
        this.rodVelocity = rodVelocity;
        this.rodFeedback = rodFeedback;
        speedKey = new NamespacedKey(plugin, "classic_attack_speed");
        savedImmunityKey = new NamespacedKey(plugin, "classic_previous_immunity");
        appliedImmunityKey = new NamespacedKey(plugin, "classic_applied_immunity");
    }
    private boolean eligible(LivingEntity entity, Settings config) {
        if (!CombatTracker.owns(entity) || players.get(entity.getUniqueId()).excluded()) return false;
        var kind = tracker.kind(entity);
        return config.affectOthers() || kind == CombatantKind.PLAYER || kind == CombatantKind.BOT;
    }
    /** Entity-region only. A transient modifier avoids overwriting item or other plugin base values. */
    public void reconcile(LivingEntity entity) {
        if (!CombatTracker.owns(entity)) return;
        Settings config = settings.get();
        if (!config.classicEnabled() || !eligible(entity, config)) { restore(entity); return; }
        managed.put(entity.getUniqueId(), entity);
        var data = entity.getPersistentDataContainer();
        if (entity instanceof Player) {
            var speed = entity.getAttribute(Attribute.ATTACK_SPEED);
            if (speed != null && speed.getModifier(speedKey) == null)
                speed.addTransientModifier(new AttributeModifier(speedKey, ATTACK_SPEED_BONUS, AttributeModifier.Operation.ADD_NUMBER));
        }
        if (!data.has(savedImmunityKey, PersistentDataType.INTEGER))
            data.set(savedImmunityKey, PersistentDataType.INTEGER, entity.getMaximumNoDamageTicks());
        int duration = config.classic().hitIntervalTicks() * 2;
        data.set(appliedImmunityKey, PersistentDataType.INTEGER, duration);
        int oldDuration = entity.getMaximumNoDamageTicks();
        if (oldDuration != duration) {
            entity.setMaximumNoDamageTicks(duration);
            int remaining = entity.getNoDamageTicks();
            // Clamp only an ordinary window from the previous duration, never a spawn/plugin protection.
            if (remaining <= oldDuration && remaining > duration) entity.setNoDamageTicks(duration);
        }
    }
    private void restore(LivingEntity entity) {
        jumps.clear(entity.getUniqueId());
        if (entity instanceof Player) {
            var speed = entity.getAttribute(Attribute.ATTACK_SPEED);
            if (speed != null && speed.getModifier(speedKey) != null) speed.removeModifier(speedKey);
        }
        var data = entity.getPersistentDataContainer();
        Integer previous = data.get(savedImmunityKey, PersistentDataType.INTEGER);
        Integer ours = data.get(appliedImmunityKey, PersistentDataType.INTEGER);
        if (previous != null && ours != null && entity.getMaximumNoDamageTicks() == ours) {
            entity.setMaximumNoDamageTicks(previous);
            int remaining = entity.getNoDamageTicks();
            if (remaining <= ours && remaining > previous) entity.setNoDamageTicks(previous);
        }
        data.remove(savedImmunityKey); data.remove(appliedImmunityKey);
        managed.remove(entity.getUniqueId(), entity);
    }
    public void refreshAll() {
        hits.clear(); pending.clear(); velocityEvents.clear(); prepared.clear(); grounds.clear(); jumps.clear(); rodHits.clear();
        Set<LivingEntity> entities = new HashSet<>(managed.values());
        entities.addAll(Bukkit.getOnlinePlayers());
        for (LivingEntity entity : entities) entity.getScheduler().run(plugin, task -> reconcile(entity), null);
    }
    public static boolean within(int previous, int now, int interval) { return Integer.toUnsignedLong(now - previous) < interval; }
    private boolean blocked(LivingEntity victim, Settings config) {
        Hit hit = hits.get(victim.getUniqueId());
        return hit != null && hit.settings == config && (hit.damage != null ? !hit.damage.isCancelled()
                : hit.knockback == null || !hit.knockback.isCancelled())
                && within(hit.tick, victim.getTicksLived(), config.classic().hitIntervalTicks());
    }
    private boolean blockedByRod(LivingEntity victim, Settings config) {
        if (activeRods.containsKey(victim.getUniqueId())) return true;
        RodStamp hit = rodHits.get(victim.getUniqueId());
        // The stamp is installed immediately after the rod damage event succeeds and before
        // knockback is dispatched. This closes the same-tick rod->left-click race. A cancelled
        // knockback removes the stamp in rodImpactResult, so it cannot leave a false lock.
        return hit != null && hit.settings == config && !hit.damage.isCancelled()
                && (hit.knockback == null || !hit.knockback.isCancelled())
                && within(hit.tick, victim.getTicksLived(), config.classic().effectiveRodMeleeTicks());
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void beforeAttack(PrePlayerAttackEntityEvent event) {
        reconcile(event.getPlayer());
        Settings config = settings.get();
        if (!config.classicEnabled() || !event.willAttack() || !(event.getAttacked() instanceof LivingEntity victim)
                || !eligible(victim, config) || players.get(event.getPlayer().getUniqueId()).excluded()) return;
        reconcile(victim);
        if (blocked(victim, config) || blockedByRod(victim, config)) { event.setCancelled(true); preventedHits.increment(); }
    }
    private boolean meleeDamage(EntityDamageByEntityEvent event) {
        return event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK;
    }
    private boolean combatDamage(EntityDamageByEntityEvent event) {
        return switch (event.getCause()) {
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK, PROJECTILE -> true;
            default -> false;
        };
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void guardDamage(EntityDamageByEntityEvent event) {
        Settings config = settings.get();
        if (!config.classicEnabled() || !(event.getEntity() instanceof LivingEntity victim) || !eligible(victim, config) || !combatDamage(event)) return;
        reconcile(victim);
        if (config.classic().disableSweep() && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                || blocked(victim, config) || meleeDamage(event) && blockedByRod(victim, config)) {
            event.setCancelled(true); preventedHits.increment();
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void acceptedDamage(EntityDamageByEntityEvent event) {
        Settings config = settings.get();
        RodScope rod = rodScope.get();
        if (rod != null && rod.damaging && event.getEntity() == rod.victim
                && event.getDamageSource().getDirectEntity() == rod.hook) {
            rod.damage = event;
            // Install the short rod->melee guard at MONITOR as soon as the damage event is
            // accepted. The final result still replaces this provisional stamp or removes it
            // if a later handler cancels the damage/KB.
            if (!event.isCancelled() && config == rod.settings && CombatTracker.owns(rod.victim))
                rodHits.put(rod.victimId, new RodStamp(config, rod.victim.getTicksLived(), event, null));
            return; // The rod pipeline checks final damage/KB before recording its follow-up window.
        }
        if (!config.classicEnabled() || !(event.getEntity() instanceof LivingEntity victim) || !eligible(victim, config)
                || !combatDamage(event) || event.getFinalDamage() <= 0) return;
        Entity source = event.getDamageSource().getDirectEntity();
        if (source == null) source = event.getDamager();
        var sourceSnapshot = tracker.source(source, victim.getUniqueId());
        Hit value = new Hit(config, victim.getTicksLived(), event, snapshot(source, victim, sourceSnapshot.sprint(), 1));
        recordHit(victim, value);
    }
    private void recordHit(LivingEntity victim, Hit value) {
        hits.put(victim.getUniqueId(), value);
        // No final vector survives beyond this tick. Timing state is removed after the immunity window.
        victim.getScheduler().runDelayed(plugin, task -> hits.remove(victim.getUniqueId(), value),
                () -> hits.remove(victim.getUniqueId(), value), value.settings.classic().hitIntervalTicks() + 1L);
    }
    private ClassicKnockbackMath.Attack snapshot(Entity source, LivingEntity victim, boolean sprint, double strength) {
        Vector direction = new Vector(), facing = new Vector(1, 0, 0);
        int enchantment = 0, punch = 0;
        if (CombatTracker.owns(source)) {
            if (source instanceof Projectile) direction = source.getVelocity();
            else direction = victim.getLocation().toVector().subtract(source.getLocation().toVector());
            Entity attacker = source;
            if (source instanceof Projectile projectile) attacker = projectile.getShooter() instanceof Entity e ? e : null;
            if (CombatTracker.owns(attacker) && attacker instanceof LivingEntity living) {
                double yaw = Math.toRadians(living.getLocation().getYaw());
                facing = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
                if (!(source instanceof Projectile) && living.getEquipment() != null)
                    enchantment = living.getEquipment().getItemInMainHand().getEnchantmentLevel(Enchantment.KNOCKBACK);
            }
            if (source instanceof AbstractArrow arrow && arrow.getWeapon() != null)
                punch = arrow.getWeapon().getEnchantmentLevel(Enchantment.PUNCH);
            // Resolve melee direction when the hit is accepted, before native knockback can
            // randomize a coincident XZ vector or a later listener changes position/yaw.
            if (source instanceof LivingEntity)
                direction = ClassicKnockbackMath.meleeDirection(direction, facing);
        }
        return new ClassicKnockbackMath.Attack(direction, facing, sprint, enchantment, punch, strength);
    }
    private boolean supported(EntityKnockbackEvent event, HitContext context) {
        return (context.type().melee() || context.type() == HitType.ARROW || context.type() == HitType.CROSSBOW
                || context.type() == HitType.FISHING_ROD || context.type() == HitType.OTHER_PROJECTILE)
                && event.getCause() != EntityKnockbackEvent.Cause.EXPLOSION
                && event.getCause() != EntityKnockbackEvent.Cause.SHIELD_BLOCK;
    }
    /** Timing belongs to classic combat, including when an API listener declines vector editing. */
    public boolean guardKnockback(EntityKnockbackEvent event, HitContext context, Settings config) {
        if (!config.classicEnabled() || !(event.getEntity() instanceof LivingEntity victim) || !supported(event, context)) return false;
        RodScope rod = rodScope.get();
        if (rod != null && rod.victim == victim && context.type() == HitType.FISHING_ROD) return false;
        Hit hit = hits.get(victim.getUniqueId());
        boolean acceptedImpulse = hit != null && hit.applied && (hit.knockback == null || !hit.knockback.isCancelled());
        boolean duplicate = hit != null && hit.settings == config && (hit.damage != null ? !hit.damage.isCancelled()
                : hit.knockback == null || !hit.knockback.isCancelled())
                && within(hit.tick, victim.getTicksLived(), config.classic().hitIntervalTicks())
                && (acceptedImpulse || hit.tick != victim.getTicksLived()
                    || hit.damage != null && victim.getLastDamageCause() != hit.damage);
        if ((context.type().melee() || context.type() == HitType.FISHING_ROD) && blockedByRod(victim, config) || duplicate) {
            event.setCancelled(true); preventedComponents.increment(); return true;
        }
        return false;
    }
    /** null leaves this type to modern handling; cancellation suppresses a repeated native component. */
    public Calculation calculate(EntityKnockbackEvent event, HitContext context, KnockbackProfile profile, Settings config) {
        if (!config.classicEnabled() || !(event.getEntity() instanceof LivingEntity victim)) return null;
        if (context.type() == HitType.SWEEP && config.classic().disableSweep()) { event.setCancelled(true); return null; }
        if (!supported(event, context)) return null;
        UUID id = victim.getUniqueId();
        int now = victim.getTicksLived();
        RodScope rod = rodScope.get();
        boolean scopedRod = rod != null && rod.victim == victim && context.type() == HitType.FISHING_ROD;
        if (!scopedRod && context.type().melee() && blockedByRod(victim, config)) {
            event.setCancelled(true); preventedComponents.increment(); return null;
        }
        if (scopedRod && (rod.damaging || rod.applied || rod.accepted
                || rod.knockback != null && rod.knockback != event)) {
            event.setCancelled(true); preventedComponents.increment(); return null;
        }
        Hit hit = scopedRod ? null : hits.get(id);
        if (hit != null && hit.damage == null && hit.knockback != null && hit.knockback.isCancelled()) hit = null;
        if (hit != null && hit.settings == config && hit.tick == now && hit.damage != null && hit.damage.isCancelled()) return null;
        if (hit != null && (hit.settings != config || hit.tick != now || hit.damage != null && victim.getLastDamageCause() != hit.damage)) hit = null;
        if (hit != null && hit.applied && (hit.knockback == null || !hit.knockback.isCancelled())) {
            event.setCancelled(true); preventedComponents.increment(); return null;
        }
        Entity source = event instanceof EntityPushedByEntityAttackEvent attack ? attack.getPushedBy() : null;
        ClassicKnockbackMath.Attack attack = scopedRod ? rod.attack : hit != null ? hit.attack : snapshot(source, victim, context.sprinting(), 1);
        Vector before = victim.getVelocity();
        if (attack.direction().lengthSquared() < 1e-12)
            attack = new ClassicKnockbackMath.Attack(event.getKnockback().clone().add(before.clone().multiply(0.5)),
                    attack.facing(), attack.sprint(), attack.enchantment(), attack.punch(), attack.strength());
        var resistance = victim.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        boolean limited = context.type().melee() && profile.classic().yLimit() && heightLimited(victim, profile);
        boolean jumpReset = config.classic().jumpResetEnabled() && context.type().melee()
                && isJumpReset(victim, context, before, config.classic().jumpResetWindowTicks());
        Vector impulse = ClassicKnockbackMath.impulse(before, context, profile, attack, resistance == null ? 0 : resistance.getValue(), limited, jumpReset);
        Hit accepted = hit;
        ClassicKnockbackMath.Attack captured = attack;
        return new Calculation(impulse, () -> {
            if (scopedRod) {
                rod.applied = true;
                Hit damageHit = hits.get(id);
                if (damageHit != null && damageHit.damage == rod.damage) damageHit.applied = true;
            } else {
                if (accepted != null) accepted.applied = true;
                else {
                    Hit synthetic = new Hit(config, now, null, captured); synthetic.applied = true; hits.put(id, synthetic);
                    victim.getScheduler().runDelayed(plugin, task -> hits.remove(id, synthetic), () -> hits.remove(id, synthetic), config.classic().hitIntervalTicks() + 1L);
                }
            }
            applied.increment();
        });
    }
    private boolean isJumpReset(LivingEntity victim, HitContext context, Vector before, int window) {
        if (!(victim instanceof Player player) || !trackJump(player, settings.get())
                || context.grounded() || before.getY() <= 0.05) return false;
        return jumps.recent(player, window, settings.get());
    }
    public void prepare(EntityKnockbackEvent event, HitContext context, Settings config, Calculation calculation) {
        RodScope rod = rodScope.get();
        if (rod != null && rod.victim == event.getEntity() && context.type() == HitType.FISHING_ROD) {
            if (rod.damaging || rod.accepted || rod.knockback != null && rod.knockback != event) {
                event.setCancelled(true); preventedComponents.increment(); return;
            }
            // Keep direct integrations compatible; registered events are captured at LOWEST.
            rod.knockback = event;
        }
        prepared.put(event, new Prepared(config, context, calculation));
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void finishedKnockback(EntityKnockbackEvent event) {
        Prepared value = prepared.remove(event);
        if (event.isCancelled()
                || !(event.getEntity() instanceof LivingEntity victim) || !CombatTracker.owns(victim)) return;
        RodScope rod = rodScope.get();
        boolean scopedRod = rod != null && !rod.damaging && rod.victim == victim && rod.knockback == event;
        if (value != null) {
            if (value.settings != settings.get() || scopedRod && rod.accepted) return;
            value.calculation.commit().run();
            Hit committed = hits.get(victim.getUniqueId());
            if (committed != null && committed.settings == value.settings && committed.tick == victim.getTicksLived())
                committed.knockback = event;
            if (scopedRod) rod.accepted = true;
            applied(victim, value.settings, value.context, event);
        } else if (scopedRod && !rod.accepted && rod.settings == settings.get()) {
            // API cancellation/disabled profiles retain the native vector. Its accepted contact
            // still needs one delivery and the full rod window, without counting a custom edit.
            rod.accepted = true;
            applied(victim, rod.settings, true, event);
        } else if (value == null && rod == null) {
            Settings config = settings.get();
            if (!config.classicEnabled() || !eligible(victim, config)) return;
            Entity source = event instanceof EntityPushedByEntityAttackEvent attack ? attack.getPushedBy() : null;
            HitContext context = tracker.context(victim, source, event.getCause().name(), false);
            if (!supported(event, context)) return;
            Hit hit = hits.get(victim.getUniqueId());
            if (hit != null && hit.settings == config && hit.tick == victim.getTicksLived()
                    && (hit.damage == null || !hit.damage.isCancelled() && victim.getLastDamageCause() == hit.damage)) {
                hit.applied = true;
                hit.knockback = event;
            } else {
                // No positive damage may exist for a native zero-hit impulse. It still consumes
                // one classic hit, without changing health or replaying its vector on another tick.
                Hit synthetic = new Hit(config, victim.getTicksLived(), null,
                        snapshot(source, victim, context.sprinting(), 1));
                synthetic.applied = true;
                synthetic.knockback = event;
                recordHit(victim, synthetic);
            }
        }
    }
    private boolean heightLimited(LivingEntity victim, KnockbackProfile profile) {
        UUID id = victim.getUniqueId();
        double y = victim.getLocation().getY();
        if (victim.isOnGround()) { grounds.put(id, new Ground(y, false)); return false; }
        Ground ground = grounds.computeIfAbsent(id, key -> new Ground(y, false));
        boolean limited = ground.limited || y - ground.y > profile.classic().maxHeight();
        if (limited != ground.limited) grounds.put(id, new Ground(ground.y, limited));
        return limited;
    }
    /** Compatibility signature; protection samples the applied entity motion when velocity is dispatched. */
    public void applied(LivingEntity victim, Vector impulse, Settings config, HitContext context) {
        applied(victim, config, context, null);
    }
    private void applied(LivingEntity victim, Settings config, HitContext context, EntityKnockbackEvent knockback) {
        applied(victim, config, context.type() == HitType.FISHING_ROD, knockback);
    }
    private void applied(LivingEntity victim, Settings config, boolean rod, EntityKnockbackEvent knockback) {
        if (!config.classic().protectVelocityFromSync() || !(victim instanceof Player)) return;
        Hit hit = hits.get(victim.getUniqueId());
        RodScope scope = rodScope.get();
        EntityDamageByEntityEvent damage = rod
                ? scope != null && scope.victim == victim ? scope.damage : null : hit == null ? null : hit.damage;
        Pending value = new Pending(config, victim.getTicksLived(), damage, knockback, rod, false, new Object());
        UUID id = victim.getUniqueId();
        pending.put(id, value);
        victim.getScheduler().runDelayed(plugin, task -> expirePending(id, value),
                () -> expirePending(id, value), rod ? 2 : 1);
    }
    private void expirePending(UUID id, Pending expected) {
        // The one Leaf follow-up shares this generation; a newer hit always has another one.
        pending.computeIfPresent(id, (key, value) -> value.generation == expected.generation ? null : value);
    }
    private boolean valid(Pending value, Player victim) {
        return value.settings == settings.get() && value.settings.classicEnabled()
                && !players.get(victim.getUniqueId()).excluded()
                && within(value.tick, victim.getTicksLived(), value.rod ? 2 : 1)
                && (value.damage == null || !value.damage.isCancelled() && victim.getLastDamageCause() == value.damage)
                && (value.knockback == null || !value.knockback.isCancelled());
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void beginVelocity(PlayerVelocityEvent event) {
        Player victim = event.getPlayer();
        if (!CombatTracker.owns(victim)) return;
        Pending value = pending.get(victim.getUniqueId());
        if (value == null) return;
        if (!valid(value, victim) || event.isCancelled()) { pending.remove(victim.getUniqueId(), value); return; }
        LeafVelocityDispatch.Stage stage = value.rod ? LeafVelocityDispatch.Stage.NONE : LeafVelocityDispatch.stage();
        if (value.leafFlush && stage != LeafVelocityDispatch.Stage.IN_FLUSH) {
            pending.remove(victim.getUniqueId(), value);
            return;
        }
        // Native melee events can contain the pre-hit motion; the entity already has the actual
        // final KB. Capture it now, before sync, including later native/event changes and movement.
        Vector current = victim.getVelocity().clone();
        if (!KnockbackMath.finite(current)) { pending.remove(victim.getUniqueId(), value); return; }
        velocityEvents.put(event, new VelocityGuard(value, current,
                !value.leafFlush && stage == LeafVelocityDispatch.Stage.BEFORE_FLUSH));
    }
    /** Finish within this velocity dispatch; never defer or replay a previous hit's motion. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void velocity(PlayerVelocityEvent event) {
        Player victim = event.getPlayer();
        VelocityGuard value = velocityEvents.remove(event);
        if (value == null || !CombatTracker.owns(victim)) return;
        // A nested hit can replace the pending token during another plugin's velocity handler.
        // The earlier event must neither replay its rod vector nor consume the new melee token.
        boolean current = pending.remove(victim.getUniqueId(), value.token);
        if (!event.isCancelled() && current && valid(value.token, victim)) {
            event.setVelocity(value.velocity);
            // KnockbackSync can change entity motion directly. Native dispatch only copies the
            // event back when it differs from its initial value, so equal vectors need this too.
            // Consume the token first; supported cores' setVelocity does not fire another event.
            if (!victim.getVelocity().equals(value.velocity)) victim.setVelocity(value.velocity.clone());
            if (value.leafFlushFollows && valid(value.token, victim)) {
                Pending token = value.token;
                // Leaf fires a second event synchronously inside its native tracker flush.
                // Give only that exact path one new token; never replay this captured vector.
                pending.putIfAbsent(victim.getUniqueId(), new Pending(token.settings, token.tick, token.damage,
                        token.knockback, false, true, token.generation));
            }
        }
    }
    public enum RodResult { INACTIVE, REJECTED, APPLIED }
    /** Compatibility entry point: true means classic owns the collision, not that KB was accepted. */
    public boolean rodImpact(FishHook hook, Player shooter, LivingEntity victim, double strength) {
        return rodImpactResult(hook, shooter, victim, strength) != RodResult.INACTIVE;
    }
    public RodResult rodImpactResult(FishHook hook, Player shooter, LivingEntity victim, double strength) {
        RodResult result = processRodImpact(hook, shooter, victim, strength);
        if (result != RodResult.INACTIVE) rodContacts.increment();
        if (result == RodResult.APPLIED) rodAccepted.increment();
        return result;
    }
    private RodResult processRodImpact(FishHook hook, Player shooter, LivingEntity victim, double strength) {
        Settings config = settings.get();
        if (!config.classicEnabled()) return RodResult.INACTIVE;
        if (!CombatTracker.owns(hook) || !CombatTracker.owns(shooter) || !eligible(victim, config)
                || shooter == victim || players.get(shooter.getUniqueId()).excluded() || victim.isDead() || victim.isInvulnerable()) {
            detachHook(hook);
            return RodResult.REJECTED;
        }
        if (!ClassicRodContact.claim(hook)) return RodResult.REJECTED;
        UUID shooterId = shooter.getUniqueId();
        reconcile(victim);
        // 1.8's projectile damage(0) cannot bypass an existing ordinary damage immunity window.
        if (blocked(victim, config) || victim.getNoDamageTicks() > victim.getMaximumNoDamageTicks() / 2
                && victim.getLastDamage() >= 0) {
            detachHook(hook);
            return RodResult.REJECTED;
        }
        RodStamp previous = rodHits.get(victim.getUniqueId());
        int now = victim.getTicksLived();
        if (activeRods.containsKey(victim.getUniqueId()) || previous != null && previous.settings == config
                && !previous.damage.isCancelled() && (previous.knockback == null || !previous.knockback.isCancelled())
                && within(previous.tick, now, config.classic().effectiveRodHitTicks())) {
            detachHook(hook);
            return RodResult.REJECTED;
        }
        int previousImmunity = victim.getNoDamageTicks();
        double previousDamage = victim.getLastDamage();
        Vector previousVelocity = victim.getVelocity().clone();
        RodScope old = rodScope.get();
        RodScope current = new RodScope(config, hook, victim, snapshot(hook, victim, false, strength / 0.4));
        if (activeRods.putIfAbsent(current.victimId, current) != null) {
            detachHook(hook);
            return RodResult.REJECTED;
        }
        rodScope.set(current);
        try {
            tracker.withSource(shooter, victim, hook, HitType.FISHING_ROD, () -> {
                DamageSource source = DamageSource.builder(DamageType.THROWN)
                        .withDirectEntity(hook).withCausingEntity(shooter).build();
                victim.damage(0, source);
                current.damaging = false;
                EntityDamageByEntityEvent damage = current.damage;
                // Check the final event after every listener, including later MONITOR handlers.
                if (damage == null || damage.isCancelled() || settings.get() != config
                        || !CombatTracker.owns(victim) || !CombatTracker.owns(shooter) || victim.isDead()
                        || victim.getLastDamageCause() != damage) return;
                rodHits.put(current.victimId, new RodStamp(config, now, damage, null));
                boolean zeroDamage = damage.getDamage() == 0 && damage.getFinalDamage() == 0;
                if (damage.getFinalDamage() > 0) recordHit(victim, new Hit(config, now, damage, current.attack));
                if (zeroDamage) {
                    int maximum = victim.getMaximumNoDamageTicks(), half = maximum / 2;
                    int remaining = victim.getNoDamageTicks();
                    // Establish the complete classic zero-hit window, as damage(0) did in 1.8.
                    // Never clear pre-existing damage immunity or an externally extended protection.
                    if (previousImmunity <= half && (remaining <= half || !(victim instanceof Player)
                            && remaining == maximum && victim.getLastDamage() == 0)) {
                        victim.setLastDamage(0);
                        int window = config.classic().effectiveRodHitTicks();
                        current.immunityWindow = half + Math.min(window, maximum - half);
                        victim.setNoDamageTicks(current.immunityWindow);
                    }
                }
                victim.knockback(strength, -current.attack.direction().getX(), -current.attack.direction().getZ());
            });
        } catch (RuntimeException | Error failure) {
            discardRod(current, previousImmunity, previousDamage);
            throw failure;
        } finally {
            activeRods.remove(current.victimId, current);
            if (old == null) rodScope.remove(); else rodScope.set(old);
            // Spend the native contact and give the retained hook its small legacy bounce.
            detachHook(hook);
        }
        if (current.accepted && current.knockback != null && !current.knockback.isCancelled()
                && current.damage != null && !current.damage.isCancelled() && settings.get() == config) {
            rodHits.computeIfPresent(current.victimId, (id, stamp) -> stamp.settings == config && stamp.damage == current.damage
                    ? new RodStamp(config, now, current.damage, current.knockback) : stamp);
            UUID victimId = current.victimId;
            BooleanSupplier currentHit = () -> {
                RodStamp stamp = rodHits.get(victimId);
                return CombatTracker.owns(victim) && CombatTracker.owns(shooter)
                        && settings.get() == config && victim.getLastDamageCause() == current.damage
                        && !current.damage.isCancelled() && !current.knockback.isCancelled()
                        && !players.get(victimId).excluded() && !players.get(shooterId).excluded()
                        && stamp != null && stamp.damage == current.damage && stamp.knockback == current.knockback;
            };
            boolean sent;
            try {
                sent = currentHit.getAsBoolean()
                        && (!(victim instanceof Player player) || rodVelocity.deliver(player, previousVelocity, currentHit));
            } catch (RuntimeException | Error failure) {
                discardRod(current, previousImmunity, previousDamage);
                throw failure;
            }
            if (sent) {
                if (currentHit.getAsBoolean() && victim instanceof Player player
                        && current.damage.getDamage() == 0 && current.damage.getFinalDamage() == 0) {
                    if (rodFeedback.play(player, current.damage.getDamageSource(), currentHit)) rodFeedbacks.increment();
                }
                return RodResult.APPLIED;
            }
        }
        discardRod(current, previousImmunity, previousDamage);
        return RodResult.REJECTED;
    }
    private void discardRod(RodScope current, int previousImmunity, double previousDamage) {
        LivingEntity victim = current.victim;
        Settings config = current.settings;
        // Remove only this rod's state, never a newer hit installed by a nested listener.
        rodHits.computeIfPresent(current.victimId, (id, stamp) ->
                stamp.settings == config && stamp.damage == current.damage ? null : stamp);
        Pending value = pending.get(current.victimId);
        if (value != null && value.settings == config && value.rod && value.damage == current.damage)
            pending.remove(current.victimId, value);
        if (CombatTracker.owns(victim) && settings.get() == config
                && current.damage != null && victim.getLastDamageCause() == current.damage
                && current.damage.getDamage() == 0 && current.damage.getFinalDamage() == 0
                && current.immunityWindow >= 0 && victim.getNoDamageTicks() == current.immunityWindow) {
            victim.setNoDamageTicks(previousImmunity);
            victim.setLastDamage(previousDamage);
        }
    }
    private void detachHook(FishHook hook) {
        if (hook == null || !CombatTracker.owns(hook)) return;
        if (settings.get().classicEnabled()) ClassicRodContact.finish(hook);
        else {
            // A mode switch during damage must not leave an old classic projectile active.
            if (hook.isValid()) hook.setHookedEntity(null);
            hook.remove();
        }
    }
    /** Suppress damage(0)'s native component and capture the one explicit rod KB before API callbacks. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void nativeRodDamageKnockback(EntityKnockbackEvent event) {
        RodScope rod = rodScope.get();
        if (rod == null || event.getEntity() != rod.victim) return;
        if (rod.damaging || rod.accepted || rod.knockback != null && rod.knockback != event) {
            event.setCancelled(true); preventedComponents.increment(); return;
        }
        rod.knockback = event;
    }
    private boolean trackJump(Player player, Settings config) {
        if (!CombatTracker.owns(player)) return false;
        boolean allowed = config.classicEnabled() && config.classic().jumpResetEnabled()
                && !players.get(player.getUniqueId()).excluded() && tracker.kind(player) == CombatantKind.PLAYER
                && !player.isFlying() && !player.isGliding() && !player.isInsideVehicle() && !player.isSwimming();
        if (!allowed) jumps.clear(player.getUniqueId());
        return allowed;
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void jump(PlayerJumpEvent event) {
        Settings config = settings.get();
        if (trackJump(event.getPlayer(), config)) jumps.jump(event, config);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void input(PlayerInputEvent event) {
        if (trackJump(event.getPlayer(), settings.get())) jumps.input(event);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void move(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) return;
        Player player = event.getPlayer();
        if (!CombatTracker.owns(player)) return;
        UUID id = player.getUniqueId();
        Settings config = settings.get();
        if (!config.classicEnabled() || !eligible(player, config)) {
            grounds.remove(id); jumps.clear(id); return;
        }
        if (trackJump(player, config)) jumps.move(event);
        if (event.isCancelled()) return;
        boolean onGround = player.isOnGround();
        if (onGround) grounds.put(id, new Ground(event.getTo().getY(), false));
    }
    @EventHandler public void join(PlayerJoinEvent event) { reconcile(event.getPlayer()); }
    @EventHandler public void added(com.destroystokyo.paper.event.entity.EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof LivingEntity living)
            living.getScheduler().run(plugin, task -> reconcile(living), null);
    }
    @EventHandler public void respawn(PlayerRespawnEvent event) {
        clear(event.getPlayer().getUniqueId());
        event.getPlayer().getScheduler().run(plugin, task -> reconcile(event.getPlayer()), null);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) { clear(event.getPlayer().getUniqueId()); }
    @EventHandler public void quit(PlayerQuitEvent event) { restore(event.getPlayer()); clear(event.getPlayer().getUniqueId()); }
    @EventHandler public void death(EntityDeathEvent event) { clear(event.getEntity().getUniqueId()); }
    @EventHandler public void removed(EntityRemoveEvent event) {
        UUID id = event.getEntity().getUniqueId();
        clear(id); managed.remove(id);
    }
    private void clear(UUID id) { hits.remove(id); pending.remove(id); grounds.remove(id); jumps.clear(id); rodHits.remove(id); }
    public long preventedHits() { return preventedHits.sum(); }
    public long preventedComponents() { return preventedComponents.sum(); }
    public long applied() { return applied.sum(); }
    public String rodStatus() {
        long accepted = rodAccepted.sum(), contacts = rodContacts.sum();
        return "Classic rod: contacts=" + contacts + " | accepted=" + accepted
                + " | rejected=" + (contacts - accepted) + " | feedback=" + rodFeedbacks.sum();
    }
    public void close() {
        // Paper owns all entities here. Folia disallows scheduling new plugin tasks during disable;
        // persistent immunity markers are recovered on the next enable/join, modifiers are transient.
        for (LivingEntity entity : new ArrayList<>(managed.values())) if (CombatTracker.owns(entity)) restore(entity);
        hits.clear(); pending.clear(); velocityEvents.clear(); prepared.clear(); grounds.clear(); jumps.clear(); rodHits.clear(); managed.clear(); rodScope.remove();
    }
}

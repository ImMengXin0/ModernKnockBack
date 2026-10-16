package cn.mx.modernkb.combat;

import cn.mx.modernkb.api.*;
import cn.mx.modernkb.config.*;
import cn.mx.modernkb.compat.RodVelocity;
import cn.mx.modernkb.rod.ClassicRodContact;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
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
        Hit(Settings settings, int tick, EntityDamageByEntityEvent damage, ClassicKnockbackMath.Attack attack) {
            this.settings = settings; this.tick = tick; this.damage = damage; this.attack = attack;
        }
    }
    private record Ground(double y, boolean limited) {}
    private record JumpStamp(int tick) {}
    private record RodStamp(Settings settings, int tick, EntityDamageByEntityEvent damage, EntityKnockbackEvent knockback) {}
    private record Pending(Settings settings, int tick, Vector velocity, EntityDamageByEntityEvent damage, boolean rod) {}
    private record VelocityGuard(Pending token, Vector velocity) {}
    private static final class RodScope {
        final Settings settings;
        final FishHook hook;
        final LivingEntity victim;
        final ClassicKnockbackMath.Attack attack;
        EntityDamageByEntityEvent damage;
        EntityKnockbackEvent knockback;
        boolean damaging = true;
        boolean applied;
        int immunityWindow = -1;
        RodScope(Settings settings, FishHook hook, LivingEntity victim, ClassicKnockbackMath.Attack attack) {
            this.settings = settings; this.hook = hook; this.victim = victim; this.attack = attack;
        }
    }
    private record Prepared(Settings settings, HitContext context, Calculation calculation) {}
    public record Calculation(Vector impulse, Runnable commit) {}
    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final PlayerStore players;
    private final CombatTracker tracker;
    private final RodVelocity rodVelocity;
    private final NamespacedKey speedKey, savedImmunityKey, appliedImmunityKey;
    private final Map<UUID, LivingEntity> managed = new ConcurrentHashMap<>();
    private final Map<UUID, Hit> hits = new ConcurrentHashMap<>();
    private final Map<UUID, Ground> grounds = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> grounded = new ConcurrentHashMap<>();
    private final Map<UUID, JumpStamp> jumps = new ConcurrentHashMap<>();
    private final Map<UUID, RodStamp> rodHits = new ConcurrentHashMap<>();
    private final Map<UUID, RodScope> activeRods = new ConcurrentHashMap<>();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<PlayerVelocityEvent, VelocityGuard> velocityEvents = new ConcurrentHashMap<>();
    private final Map<EntityKnockbackEvent, Prepared> prepared = new ConcurrentHashMap<>();
    private final ThreadLocal<RodScope> rodScope = new ThreadLocal<>();
    private final LongAdder preventedHits = new LongAdder(), preventedComponents = new LongAdder(), applied = new LongAdder();

    public ClassicCombat(Plugin plugin, Supplier<Settings> settings, PlayerStore players, CombatTracker tracker) {
        this(plugin, settings, players, tracker, RodVelocity.deferred());
    }
    public ClassicCombat(Plugin plugin, Supplier<Settings> settings, PlayerStore players, CombatTracker tracker, RodVelocity rodVelocity) {
        this.plugin = plugin; this.settings = settings; this.players = players; this.tracker = tracker;
        this.rodVelocity = rodVelocity;
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
        grounded.putIfAbsent(entity.getUniqueId(), entity.isOnGround());
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
        hits.clear(); pending.clear(); velocityEvents.clear(); prepared.clear(); grounds.clear(); grounded.clear(); jumps.clear(); rodHits.clear();
        Set<LivingEntity> entities = new HashSet<>(managed.values());
        entities.addAll(Bukkit.getOnlinePlayers());
        for (LivingEntity entity : entities) entity.getScheduler().run(plugin, task -> reconcile(entity), null);
    }
    public static boolean within(int previous, int now, int interval) { return Integer.toUnsignedLong(now - previous) < interval; }
    private boolean blocked(LivingEntity victim, Settings config) {
        Hit hit = hits.get(victim.getUniqueId());
        return hit != null && hit.settings == config && (hit.damage == null || !hit.damage.isCancelled())
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
            if (!event.isCancelled() && config == rod.settings)
                rodHits.put(rod.victim.getUniqueId(), new RodStamp(config, rod.victim.getTicksLived(), event, null));
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
    /** null means leave this type to modern impulse handling; cancelled means suppress a repeated native component. */
    public Calculation calculate(EntityKnockbackEvent event, HitContext context, KnockbackProfile profile, Settings config) {
        if (!config.classicEnabled() || !(event.getEntity() instanceof LivingEntity victim)) return null;
        if (context.type() == HitType.SWEEP && config.classic().disableSweep()) { event.setCancelled(true); return null; }
        boolean supported = context.type().melee() || context.type() == HitType.ARROW || context.type() == HitType.CROSSBOW
                || context.type() == HitType.FISHING_ROD || context.type() == HitType.OTHER_PROJECTILE;
        if (!supported || event.getCause() == EntityKnockbackEvent.Cause.EXPLOSION || event.getCause() == EntityKnockbackEvent.Cause.SHIELD_BLOCK) return null;
        UUID id = victim.getUniqueId();
        int now = victim.getTicksLived();
        RodScope rod = rodScope.get();
        boolean scopedRod = rod != null && rod.victim == victim && context.type() == HitType.FISHING_ROD;
        if (!scopedRod && context.type().melee() && blockedByRod(victim, config)) {
            event.setCancelled(true); preventedComponents.increment(); return null;
        }
        if (scopedRod && rod.applied) { event.setCancelled(true); preventedComponents.increment(); return null; }
        Hit hit = scopedRod ? null : hits.get(id);
        if (hit != null && hit.settings == config && hit.tick == now && hit.damage != null && hit.damage.isCancelled()) return null;
        if (hit != null && (hit.settings != config || hit.tick != now || hit.damage != null && victim.getLastDamageCause() != hit.damage)) hit = null;
        if (hit != null && hit.applied) { event.setCancelled(true); preventedComponents.increment(); return null; }
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
        if (!(victim instanceof Player) || context.grounded() || before.getY() <= 0.05) return false;
        JumpStamp stamp = jumps.get(victim.getUniqueId());
        return stamp != null && within(stamp.tick(), victim.getTicksLived(), window + 1);
    }
    public void prepare(EntityKnockbackEvent event, HitContext context, Settings config, Calculation calculation) {
        prepared.put(event, new Prepared(config, context, calculation));
        RodScope rod = rodScope.get();
        if (rod != null && rod.victim == event.getEntity() && context.type() == HitType.FISHING_ROD) rod.knockback = event;
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void finishedKnockback(EntityKnockbackEvent event) {
        Prepared value = prepared.remove(event);
        if (value == null || event.isCancelled() || value.settings != settings.get()
                || !(event.getEntity() instanceof LivingEntity victim) || !CombatTracker.owns(victim)) return;
        value.calculation.commit().run();
        applied(victim, event.getKnockback(), value.settings, value.context);
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
    public void applied(LivingEntity victim, Vector impulse, Settings config, HitContext context) {
        if (!config.classic().protectVelocityFromSync() || !(victim instanceof Player)) return;
        Hit hit = hits.get(victim.getUniqueId());
        boolean rod = context.type() == HitType.FISHING_ROD;
        RodScope scope = rodScope.get();
        EntityDamageByEntityEvent damage = rod
                ? scope != null && scope.victim == victim ? scope.damage : null : hit == null ? null : hit.damage;
        Pending value = new Pending(config, victim.getTicksLived(), victim.getVelocity().clone().add(impulse),
                damage, rod);
        UUID id = victim.getUniqueId();
        pending.put(id, value);
        victim.getScheduler().runDelayed(plugin, task -> pending.remove(id, value), () -> pending.remove(id, value), rod ? 2 : 1);
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void beginVelocity(PlayerVelocityEvent event) {
        Player victim = event.getPlayer();
        if (!CombatTracker.owns(victim)) return;
        Pending value = pending.get(victim.getUniqueId());
        if (value == null) return;
        boolean valid = value.settings == settings.get() && within(value.tick, victim.getTicksLived(), value.rod ? 2 : 1)
                && (value.damage == null || !value.damage.isCancelled() && victim.getLastDamageCause() == value.damage);
        if (!valid || event.isCancelled()) { pending.remove(victim.getUniqueId(), value); return; }
        // Rod delivery is immediate in the plugin; compatibility callers may flush on the next tick.
        // Preserve current rod motion rather than replaying a frozen collision vector.
        velocityEvents.put(event, new VelocityGuard(value, value.rod ? victim.getVelocity().clone() : value.velocity.clone()));
    }
    /** Registered after soft-dependent KnockbackSync. No setVelocity call and no recursive event. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void velocity(PlayerVelocityEvent event) {
        Player victim = event.getPlayer();
        if (!CombatTracker.owns(victim)) return;
        VelocityGuard value = velocityEvents.remove(event);
        if (value == null) return;
        // A nested hit can replace the pending token during another plugin's velocity handler.
        // The earlier event must neither replay its rod vector nor consume the new melee token.
        boolean current = pending.remove(victim.getUniqueId(), value.token);
        if (!event.isCancelled() && current && value.token.settings == settings.get()) event.setVelocity(value.velocity);
    }
    public enum RodResult { INACTIVE, REJECTED, APPLIED }
    /** Compatibility entry point: true means classic owns the collision, not that KB was accepted. */
    public boolean rodImpact(FishHook hook, Player shooter, LivingEntity victim, double strength) {
        return rodImpactResult(hook, shooter, victim, strength) != RodResult.INACTIVE;
    }
    public RodResult rodImpactResult(FishHook hook, Player shooter, LivingEntity victim, double strength) {
        Settings config = settings.get();
        if (!config.classicEnabled()) return RodResult.INACTIVE;
        if (!CombatTracker.owns(hook) || !CombatTracker.owns(shooter) || !eligible(victim, config)
                || shooter == victim || players.get(shooter.getUniqueId()).excluded() || victim.isDead() || victim.isInvulnerable()) {
            detachHook(hook);
            return RodResult.REJECTED;
        }
        if (!ClassicRodContact.claim(hook)) return RodResult.REJECTED;
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
        if (activeRods.putIfAbsent(victim.getUniqueId(), current) != null) {
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
                if (damage == null || damage.isCancelled() || settings.get() != config || victim.isDead()
                        || victim.getLastDamageCause() != damage) return;
                rodHits.put(victim.getUniqueId(), new RodStamp(config, now, damage, null));
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
            activeRods.remove(victim.getUniqueId(), current);
            if (old == null) rodScope.remove(); else rodScope.set(old);
            // Never leave a classic hit bobber attached, including cancelled/immune hits.
            detachHook(hook);
        }
        if (current.applied && current.knockback != null && !current.knockback.isCancelled()
                && current.damage != null && !current.damage.isCancelled() && settings.get() == config) {
            rodHits.computeIfPresent(victim.getUniqueId(), (id, stamp) -> stamp.settings == config && stamp.damage == current.damage
                    ? new RodStamp(config, now, current.damage, current.knockback) : stamp);
            boolean sent;
            try {
                sent = !(victim instanceof Player player) || rodVelocity.deliver(player, previousVelocity, () -> {
                    RodStamp stamp = rodHits.get(victim.getUniqueId());
                    return settings.get() == config && victim.getLastDamageCause() == current.damage
                            && !current.damage.isCancelled() && stamp != null && stamp.damage == current.damage;
                });
            } catch (RuntimeException | Error failure) {
                discardRod(current, previousImmunity, previousDamage);
                throw failure;
            }
            if (sent) {
                if (victim instanceof Player && current.damage.getDamage() == 0 && current.damage.getFinalDamage() == 0) {
                    double yaw = Math.toDegrees(Math.atan2(-current.attack.direction().getX(), current.attack.direction().getZ()));
                    victim.playHurtAnimation((float)(yaw - victim.getLocation().getYaw()));
                    var sound = victim.getHurtSound();
                    if (sound != null) victim.getWorld().playSound(victim, sound, SoundCategory.PLAYERS, 1, 1);
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
        rodHits.computeIfPresent(victim.getUniqueId(), (id, stamp) ->
                stamp.settings == config && stamp.damage == current.damage ? null : stamp);
        if (settings.get() == config && current.damage != null && victim.getLastDamageCause() == current.damage
                && current.damage.getDamage() == 0 && current.damage.getFinalDamage() == 0
                && current.immunityWindow >= 0 && victim.getNoDamageTicks() == current.immunityWindow) {
            victim.setNoDamageTicks(previousImmunity);
            victim.setLastDamage(previousDamage);
        }
        Pending value = pending.get(victim.getUniqueId());
        if (value != null && value.settings == config && value.rod && value.damage == current.damage)
            pending.remove(victim.getUniqueId(), value);
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
    /** Non-player entities may emit native KB during damage(0); apply the classic vector once afterwards. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void nativeRodDamageKnockback(EntityKnockbackEvent event) {
        RodScope rod = rodScope.get();
        if (rod != null && rod.damaging && event.getEntity() == rod.victim) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) return;
        if (!settings.get().classicEnabled()) return;
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        boolean onGround = player.isOnGround();
        Boolean wasGround = grounded.put(id, onGround);
        if (onGround) {
            grounds.put(id, new Ground(event.getTo().getY(), false));
            jumps.remove(id);
        } else if (Boolean.TRUE.equals(wasGround) && player.getVelocity().getY() > 0.05) {
            jumps.put(id, new JumpStamp(player.getTicksLived()));
        }
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
    private void clear(UUID id) { hits.remove(id); pending.remove(id); grounds.remove(id); grounded.remove(id); jumps.remove(id); rodHits.remove(id); }
    public long preventedHits() { return preventedHits.sum(); }
    public long preventedComponents() { return preventedComponents.sum(); }
    public long applied() { return applied.sum(); }
    public void close() {
        // Paper owns all entities here. Folia disallows scheduling new plugin tasks during disable;
        // persistent immunity markers are recovered on the next enable/join, modifiers are transient.
        for (LivingEntity entity : new ArrayList<>(managed.values())) if (CombatTracker.owns(entity)) restore(entity);
        hits.clear(); pending.clear(); velocityEvents.clear(); prepared.clear(); grounds.clear(); grounded.clear(); jumps.clear(); rodHits.clear(); managed.clear(); rodScope.remove();
    }
}

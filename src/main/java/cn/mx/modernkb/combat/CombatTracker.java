package cn.mx.modernkb.combat;

import cn.mx.modernkb.api.*;
import cn.mx.modernkb.config.Settings;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public final class CombatTracker implements Listener {
    public record Source(UUID id, UUID attacker, CombatantKind kind, HitType type, boolean sprint, double charge) {}
    private record Attack(UUID target, int tick, boolean sprint, double charge) {}
    private record Damage(EntityDamageByEntityEvent event, int tick, Source source) {}
    private record Scope(UUID attacker, UUID victim, HitType type, Source source) {}
    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final NamespacedKey botKey;
    private final Map<UUID, Attack> attacks = new ConcurrentHashMap<>();
    private final Map<UUID, Damage> damages = new ConcurrentHashMap<>();
    // Client START/STOP state is separate from the entity state reset by native attacks.
    private final Map<UUID, Boolean> clientSprints = new ConcurrentHashMap<>();
    private final ThreadLocal<Scope> scope = new ThreadLocal<>();

    public CombatTracker(Plugin plugin, Supplier<Settings> settings) {
        this.plugin = plugin; this.settings = settings; botKey = new NamespacedKey(plugin, "bot");
    }
    public static boolean owns(Entity entity) { return entity != null && Bukkit.isOwnedByCurrentRegion(entity); }
    public CombatantKind kind(Entity entity) {
        if (!owns(entity)) return CombatantKind.UNKNOWN;
        Byte marked = entity.getPersistentDataContainer().get(botKey, PersistentDataType.BYTE);
        if (marked != null) return marked != 0 ? CombatantKind.BOT : entity instanceof Player ? CombatantKind.PLAYER : CombatantKind.OTHER;
        for (String key : settings.get().botMetadata()) if (entity.hasMetadata(key)) return CombatantKind.BOT;
        return entity instanceof Player ? CombatantKind.PLAYER : CombatantKind.OTHER;
    }
    public void setBot(Entity entity, Boolean bot) {
        if (!owns(entity)) throw new IllegalStateException("setBot requires the entity region");
        if (bot == null) entity.getPersistentDataContainer().remove(botKey);
        else entity.getPersistentDataContainer().set(botKey, PersistentDataType.BYTE, (byte)(bot ? 1 : 0));
    }
    public void withType(LivingEntity attacker, LivingEntity victim, HitType type, Runnable action) {
        withSource(attacker, victim, attacker, type, action);
    }
    /** Preserve a projectile as the direct source when a public knockback call has no native source. */
    public void withSource(LivingEntity attacker, LivingEntity victim, Entity direct, HitType type, Runnable action) {
        Objects.requireNonNull(type); Objects.requireNonNull(action);
        if (!owns(attacker) || !owns(victim) || !owns(direct)) throw new IllegalStateException("withHitType requires all source and victim regions");
        Scope previous = scope.get();
        scope.set(new Scope(attacker.getUniqueId(), victim.getUniqueId(), type, source(direct, victim.getUniqueId())));
        try { action.run(); }
        finally { if (previous == null) scope.remove(); else scope.set(previous); }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void preAttack(PrePlayerAttackEntityEvent event) {
        if (!settings.get().enabled() || !event.willAttack() || !owns(event.getPlayer()) || !owns(event.getAttacked())) return;
        Player p = event.getPlayer();
        Attack value = new Attack(event.getAttacked().getUniqueId(), p.getTicksLived(), sprint(p), p.getAttackCooldown());
        UUID id = p.getUniqueId();
        attacks.put(id, value);
        if (p.getScheduler().run(plugin, task -> attacks.remove(id, value), () -> attacks.remove(id, value)) == null) attacks.remove(id, value);
    }
    public Source source(Entity entity, UUID victim) {
        if (!owns(entity)) return new Source(null, null, CombatantKind.UNKNOWN, HitType.UNKNOWN, false, Double.NaN);
        Entity attacker = entity;
        if (entity instanceof Projectile projectile) {
            var shooter = projectile.getShooter();
            attacker = shooter instanceof Entity e ? e : null;
        }
        boolean sprint = false;
        double charge = Double.NaN;
        if (owns(attacker) && attacker instanceof Player p) {
            Attack attack = attacks.get(p.getUniqueId());
            if (attack != null && attack.tick == p.getTicksLived() && attack.target.equals(victim)) {
                sprint = attack.sprint; charge = attack.charge;
            } else sprint = sprint(p);
        }
        return new Source(entity.getUniqueId(), owns(attacker) ? attacker.getUniqueId() : null,
                kind(attacker), HitClassifier.source(entity), sprint, charge);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damage(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        if (!settings.get().enabled() || !owns(victim)) return;
        CombatantKind victimKind = kind(victim);
        if (!settings.get().affectOthers() && victimKind != CombatantKind.PLAYER && victimKind != CombatantKind.BOT) return;
        UUID id = victim.getUniqueId();
        Entity direct = event.getDamageSource().getDirectEntity();
        if (direct == null) direct = event.getDamager();
        Damage damage = new Damage(event, victim.getTicksLived(), source(direct, id));
        damages.put(id, damage);
        if (victim.getScheduler().run(plugin, task -> damages.remove(id, damage), () -> damages.remove(id, damage)) == null) damages.remove(id, damage);
    }
    public HitContext context(Entity victim, Entity pushedBy, String cause, boolean sync) {
        UUID id = victim.getUniqueId();
        Source source = source(pushedBy, id);
        Damage damage = damages.get(id);
        boolean critical = false;
        // Identity, victim tick and source checks prevent stale last-hit attribution.
        if (damage != null && damage.tick == victim.getTicksLived() && !damage.event.isCancelled()
                && victim.getLastDamageCause() == damage.event
                && (source.id == null && (cause.equals("DAMAGE") || cause.equals("EXPLOSION"))
                    || source.id != null && (source.id.equals(damage.source.id) || source.id.equals(damage.source.attacker)))) {
            source = damage.source;
            critical = damage.event.isCritical();
        }
        HitType type = HitClassifier.cause(cause, source.type);
        Scope explicit = scope.get();
        if (explicit != null && explicit.victim.equals(id) && (explicit.attacker.equals(source.attacker)
                || pushedBy == null && cause.equals("UNKNOWN"))) {
            type = explicit.type;
            if (source.id == null) source = explicit.source;
        }
        CombatantKind victimKind = kind(victim);
        boolean preserveY = sync && type.melee() && source.kind == CombatantKind.PLAYER && victimKind == CombatantKind.PLAYER;
        return new HitContext(id, source.attacker, source.id, type, source.kind, victimKind, cause,
                victim.isOnGround(), source.sprint, critical, source.charge, preserveY);
    }
    private boolean sprint(Player player) {
        boolean server = player.isSprinting();
        // Player-shaped NPCs may never send client sprint actions. Always use their entity state.
        if (kind(player) == CombatantKind.BOT) return server;
        boolean client = clientSprints.computeIfAbsent(player.getUniqueId(), id -> server);
        Settings config = settings.get();
        return config.classicEnabled() && !config.classic().stopSprint() ? client : server;
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void toggleSprint(PlayerToggleSprintEvent event) {
        if (event.isCancelled() || !owns(event.getPlayer())) return;
        Player player = event.getPlayer();
        if (kind(player) == CombatantKind.BOT) return;
        // Track in both modes so switching/reloading during a held sprint keeps the last client action.
        clientSprints.put(player.getUniqueId(), event.isSprinting());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (owns(player)) clientSprints.put(player.getUniqueId(), player.isSprinting());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void respawn(PlayerRespawnEvent event) { clearPlayer(event.getPlayer().getUniqueId()); }
    @EventHandler public void quit(PlayerQuitEvent event) {
        clearPlayer(event.getPlayer().getUniqueId());
    }
    private void clearPlayer(UUID id) { attacks.remove(id); damages.remove(id); clientSprints.remove(id); }
    /** Configuration refresh clears hit snapshots, not the client's still-held sprint action. */
    public void clear() { attacks.clear(); damages.clear(); scope.remove(); }
    public void close() { clear(); clientSprints.clear(); }
}

package cn.mx.modernkb.rod;

import cn.mx.modernkb.combat.CombatTracker;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/** A retained bobber can spend its one contact only once, including after a listener reload. */
public final class ClassicRodContact {
    private static final NamespacedKey CONTACT = new NamespacedKey("modernknockback", "classic_rod_contact");
    private ClassicRodContact() {}

    public static boolean spent(FishHook hook) {
        return hook.getPersistentDataContainer().get(CONTACT, PersistentDataType.BYTE) != null;
    }
    public static boolean claim(FishHook hook) {
        if (!hook.isValid() || spent(hook)) return false;
        hook.getPersistentDataContainer().set(CONTACT, PersistentDataType.BYTE, (byte) 1);
        return true;
    }
    /** Only the completed, accepted damage/velocity pipeline may attach a spent contact. */
    public static boolean attach(FishHook hook, LivingEntity victim, Plugin plugin) {
        return attach(hook, victim, plugin, () -> true);
    }
    public static boolean attach(FishHook hook, LivingEntity victim, Plugin plugin, BooleanSupplier current) {
        Objects.requireNonNull(current, "current");
        if (!canAttach(hook, victim, plugin) || !current.getAsBoolean()) return false;
        var data = hook.getPersistentDataContainer();
        Byte phase = data.get(CONTACT, PersistentDataType.BYTE);
        if (phase == null) return false;
        if (phase == 3) return hook.getHookedEntity() == victim;
        Player viewer = victim instanceof Player player ? player : null;
        boolean hidden = false;
        try {
            if (viewer != null) {
                // Hide before publishing attachment metadata so the hook cannot cover this
                // viewer's face. Other viewers keep the native bobber and fishing line.
                hidden = true;
                viewer.hideEntity(plugin, hook);
            }
            // PlayerHideEntityEvent listeners may remove/teleport either entity.
            if (!canAttach(hook, victim, plugin) || !current.getAsBoolean()) {
                if (hidden) restoreVisibility(viewer, hook, plugin);
                return false;
            }
            hook.setHookedEntity(victim);
            hook.setVelocity(new Vector());
            data.set(CONTACT, PersistentDataType.BYTE, (byte) 3);
            return true;
        } catch (RuntimeException | Error failure) {
            try {
                if (CombatTracker.owns(hook) && hook.isValid()) hook.setHookedEntity(null);
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            try {
                if (hidden) restoreVisibility(viewer, hook, plugin);
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }
    private static boolean canAttach(FishHook hook, LivingEntity victim, Plugin plugin) {
        return hook != null && victim != null && plugin != null && plugin.isEnabled()
                && CombatTracker.owns(hook) && CombatTracker.owns(victim)
                && hook.isValid() && victim.isValid() && !victim.isDead()
                && hook.getWorld() == victim.getWorld();
    }
    private static void restoreVisibility(Player viewer, FishHook hook, Plugin plugin) {
        // showEntity also reads the hook's native handle on Folia, so owning only the
        // viewer's region is insufficient. Removal itself clears core visibility records.
        if (CombatTracker.owns(viewer) && CombatTracker.owns(hook)) viewer.showEntity(plugin, hook);
    }
    /** Call after capturing/applying the incoming hit direction, never before it. */
    public static void finish(FishHook hook) {
        if (hook == null || !CombatTracker.owns(hook) || !hook.isValid()) return;
        var data = hook.getPersistentDataContainer();
        Byte phase = data.get(CONTACT, PersistentDataType.BYTE);
        if (phase != null && phase == 3) return;
        hook.setHookedEntity(null);
        if (phase != null && phase == 2) return;
        data.set(CONTACT, PersistentDataType.BYTE, (byte) 2);
        // Rejected/immune contacts remain spent and fall without a sideways rebound.
        hook.setVelocity(new Vector(0, -0.05, 0));
    }
}

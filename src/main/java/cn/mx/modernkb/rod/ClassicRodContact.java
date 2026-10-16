package cn.mx.modernkb.rod;

import cn.mx.modernkb.combat.CombatTracker;
import cn.mx.modernkb.combat.KnockbackMath;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.FishHook;
import org.bukkit.persistence.PersistentDataType;
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
    /** Call after capturing/applying the incoming hit direction, never before it. */
    public static void finish(FishHook hook) {
        if (hook == null || !CombatTracker.owns(hook) || !hook.isValid()) return;
        var data = hook.getPersistentDataContainer();
        Byte phase = data.get(CONTACT, PersistentDataType.BYTE);
        hook.setHookedEntity(null);
        if (phase != null && phase == 2) return;
        data.set(CONTACT, PersistentDataType.BYTE, (byte) 2);
        // A small backward/downward bounce preserves the bobber and line without riding
        // the victim's face. Repeated contacts must not reverse or accelerate it again.
        Vector incoming = hook.getVelocity();
        Vector bounce = KnockbackMath.finite(incoming)
                ? new Vector(-incoming.getX() * 0.1, -Math.max(0.05, Math.abs(incoming.getY()) * 0.1), -incoming.getZ() * 0.1)
                : new Vector(0, -0.05, 0);
        hook.setVelocity(bounce);
    }
}

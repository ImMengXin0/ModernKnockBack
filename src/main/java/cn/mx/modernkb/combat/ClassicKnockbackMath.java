package cn.mx.modernkb.combat;

import cn.mx.modernkb.api.HitContext;
import cn.mx.modernkb.config.KnockbackProfile;
import org.bukkit.util.Vector;

/** A deterministic classic final velocity, converted to a Paper delta only at the boundary. */
public final class ClassicKnockbackMath {
    // LivingEntity.knockback randomizes direction below this horizontal squared distance.
    // Classic uses the attacker's captured yaw instead, including when attacker/victim Y differs.
    private static final double MELEE_DIRECTION_EPSILON_SQUARED = 1.0e-5;
    private ClassicKnockbackMath() {}
    public record Attack(Vector direction, Vector facing, boolean sprint, int enchantment, int punch, double strength) {}
    public static Vector impulse(Vector before, HitContext hit, KnockbackProfile profile, Attack attack,
                                 double resistance, boolean heightLimited) {
        return impulse(before, hit, profile, attack, resistance, heightLimited, false);
    }
    /**
     * The classic jump-reset policy retains half the existing motion for a confirmed jump.
     * It is a state flag, not another KB multiplier or a full legacy physics simulation.
     */
    public static Vector impulse(Vector before, HitContext hit, KnockbackProfile profile, Attack attack,
                                 double resistance, boolean heightLimited, boolean jumpReset) {
        var classic = profile.classic();
        boolean melee = hit.type().melee();
        Vector direction = melee ? meleeDirection(attack.direction(), attack.facing())
                : horizontal(attack.direction(), attack.facing());
        double horizontal = (hit.grounded() ? classic.horizontalGround() : classic.horizontalAir()) * attack.strength();
        double vertical = (hit.grounded() ? classic.verticalGround() : classic.verticalAir()) * attack.strength();
        if (melee) {
            double bonus = attack.enchantment() * classic.enchantmentHorizontal() + (attack.sprint() ? classic.sprintHorizontal() : 0);
            // One hit has one direction. Mixing radial and facing vectors changes strength with yaw.
            horizontal += bonus;
            if (bonus > 0) vertical += classic.sprintVertical();
        } else if (attack.punch() > 0) horizontal += 0.6 * attack.punch();
        Vector push = direction.multiply(horizontal);
        double resistanceScale = classic.respectResistance() ? 1 - Math.clamp(resistance, 0, 1) : 1;
        double horizontalRetention = classic.horizontalRetention();
        double verticalRetention = classic.verticalRetention();
        if (jumpReset) {
            horizontalRetention = Math.max(horizontalRetention, 0.5);
            verticalRetention = Math.max(verticalRetention, 0.5);
        }
        Vector result = new Vector(before.getX() * horizontalRetention + push.getX() * resistanceScale,
                Math.min(classic.verticalMax(), before.getY() * verticalRetention + vertical * resistanceScale),
                before.getZ() * horizontalRetention + push.getZ() * resistanceScale);
        if (melee && classic.yLimit() && heightLimited) result.setY(classic.verticalAfterLimit());
        // A modern impulse cap would make the final velocity depend on the victim's old momentum.
        return result.subtract(before);
    }
    static Vector meleeDirection(Vector displacement, Vector facing) {
        double length = KnockbackMath.finite(displacement) ? Math.hypot(displacement.getX(), displacement.getZ()) : 0;
        return horizontal(length * length >= MELEE_DIRECTION_EPSILON_SQUARED
                ? displacement : facing, facing);
    }
    static Vector horizontal(Vector preferred, Vector fallback) {
        Vector direction = preferred;
        if (!KnockbackMath.finite(direction) || Math.hypot(direction.getX(), direction.getZ()) < 1e-8) direction = fallback;
        if (!KnockbackMath.finite(direction) || Math.hypot(direction.getX(), direction.getZ()) < 1e-8) return new Vector(1, 0, 0);
        double length = Math.hypot(direction.getX(), direction.getZ());
        return new Vector(direction.getX() / length, 0, direction.getZ() / length);
    }
}

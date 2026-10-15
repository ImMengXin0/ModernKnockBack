package cn.mx.modernkb.combat;

import cn.mx.modernkb.api.HitContext;
import cn.mx.modernkb.api.HitType;
import cn.mx.modernkb.config.KnockbackProfile;
import com.destroystokyo.paper.event.entity.EntityKnockbackByEntityEvent;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.util.Vector;

/** Reconstruct only the verified LivingEntity.knockback formula, never explosion or push formulas. */
public final class NativeKnockbackMath {
    private NativeKnockbackMath() {}
    public record Result(Vector impulse, boolean nativeApplied, String fallbackReason) {}

    public static boolean supports(EntityKnockbackEvent event, HitContext hit) {
        return switch (event.getCause()) {
            case ENTITY_ATTACK, DAMAGE, SWEEP_ATTACK, SHIELD_BLOCK, UNKNOWN ->
                    event instanceof EntityKnockbackByEntityEvent
                    || event.getCause() == EntityKnockbackEvent.Cause.UNKNOWN && hit.type() == HitType.FISHING_ROD;
            default -> false;
        };
    }

    /** strength is already resistance-adjusted by the server; NaN is only used for our scoped rod call. */
    public static Result calculate(Vector delta, Vector before, double strength, HitContext hit, KnockbackProfile profile) {
        if (!KnockbackMath.finite(delta)) throw new IllegalArgumentException("Non-finite native impulse");
        if (!profile.enabled() || !profile.nativeSettings().enabled()) return fallback(delta, hit, profile, "");
        if (!KnockbackMath.finite(before)) return fallback(delta, hit, profile, "invalid-velocity");
        // nativeFinalXZ = beforeXZ/2 + pushXZ, so pushXZ = deltaXZ + beforeXZ/2.
        double pushX = delta.getX() + before.getX() * 0.5;
        double pushZ = delta.getZ() + before.getZ() * 0.5;
        double measuredStrength = Math.hypot(pushX, pushZ);
        if (Double.isNaN(strength)) strength = measuredStrength;
        if (!Double.isFinite(strength) || strength <= 0 || !close(measuredStrength, strength))
            return fallback(delta, hit, profile, "strength-mismatch");
        double expectedY = hit.grounded() ? Math.min(0.4, before.getY() * 0.5 + strength) - before.getY() : 0;
        if (!close(delta.getY(), expectedY)) return fallback(delta, hit, profile, "vertical-mismatch");

        var nativeConfig = profile.nativeSettings();
        double extra = KnockbackMath.extraMultiplier(hit, profile);
        double h = (hit.grounded() ? profile.groundHorizontal() : profile.airHorizontal()) * extra;
        double v = (hit.grounded() ? profile.groundVertical() : profile.airVertical()) * extra;
        double retention = hit.grounded() ? nativeConfig.groundHorizontalRetention() : nativeConfig.airHorizontalRetention();
        // Algebraic correction preserves the original vector exactly with identity parameters.
        Vector result = delta.clone();
        result.setX(delta.getX() + before.getX() * (retention - 0.5) + pushX * (h - 1));
        result.setZ(delta.getZ() + before.getZ() * (retention - 0.5) + pushZ * (h - 1));
        if (!hit.syncOwnsVertical()) {
            if (hit.grounded()) {
                if (nativeConfig.groundVerticalRetention() != 0.5 || nativeConfig.groundVerticalMax() != 0.4 || v != 1)
                    result.setY(Math.min(nativeConfig.groundVerticalMax(),
                            before.getY() * nativeConfig.groundVerticalRetention() + strength * v) - before.getY());
            } else if (nativeConfig.addAirVertical()) {
                // Add up to the cap, but never reduce an existing faster ascent just to satisfy it.
                double addition = Math.max(0, Math.min(strength * v, nativeConfig.airVerticalMax() - before.getY()));
                result.setY(delta.getY() + addition);
            }
        }
        if (!KnockbackMath.finite(result)) return fallback(delta, hit, profile, "non-finite-result");
        return new Result(KnockbackMath.limit(result, hit, profile), true, "");
    }

    public static Result fallback(Vector delta, HitContext hit, KnockbackProfile profile, String reason) {
        return new Result(KnockbackMath.modify(delta, hit, profile), false, reason);
    }
    private static boolean close(double actual, double expected) {
        // Paper exposes strength as float; allow its rounding, not arbitrary plugin modifications.
        return Double.isFinite(actual) && Math.abs(actual - expected) <= 1e-6 * Math.max(1, Math.abs(expected));
    }
}

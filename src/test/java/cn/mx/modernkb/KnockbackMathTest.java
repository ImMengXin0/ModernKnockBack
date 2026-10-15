package cn.mx.modernkb;

import cn.mx.modernkb.api.*;
import cn.mx.modernkb.combat.KnockbackMath;
import cn.mx.modernkb.config.KnockbackProfile;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Vector;
import org.junit.Test;
import static org.junit.Assert.*;

public class KnockbackMathTest {
    static HitContext hit(HitType type, boolean ground, boolean sprint, boolean crit, double charge, boolean sync) {
        return new HitContext(null, null, null, type, CombatantKind.PLAYER, CombatantKind.PLAYER,
                "ENTITY_ATTACK", ground, sprint, crit, charge, sync);
    }
    static KnockbackProfile profile(Object... values) {
        var yaml = new YamlConfiguration();
        for (int i = 0; i < values.length; i += 2) yaml.set((String)values[i], values[i + 1]);
        return KnockbackProfile.read(yaml);
    }
    @Test public void defaultsPreserveEveryNativeCauseIncludingNegativeMomentum() {
        for (HitType type : HitType.values()) for (boolean ground : new boolean[]{true, false}) {
            Vector nativeDelta = new Vector(-0.273, -0.1, 0.167);
            assertEquals(nativeDelta, KnockbackMath.modify(nativeDelta, hit(type, ground, true, true, 0.23, false), profile()));
            assertEquals(new Vector(-0.273, -0.1, 0.167), nativeDelta);
        }
    }
    @Test public void selectsGroundAndAirIndependently() {
        var p = profile("horizontal.ground", 2, "horizontal.air", 3, "vertical.ground", 0.5, "vertical.air", 0.25);
        assertEquals(new Vector(2, 0.5, 2), KnockbackMath.modify(new Vector(1, 1, 1), hit(HitType.MELEE, true, false, false, 1, false), p));
        assertEquals(new Vector(3, 0.25, 3), KnockbackMath.modify(new Vector(1, 1, 1), hit(HitType.MELEE, false, false, false, 1, false), p));
    }
    @Test public void syncYSurvivesScalingAndBothCaps() {
        var p = profile("vertical.ground", 3, "limits.upward", 0.1, "limits.downward", 0.1);
        for (double y : new double[]{-0.8, 0.8}) assertEquals(y, KnockbackMath.modify(new Vector(0, y, 0), hit(HitType.MELEE, true, false, false, 1, true), p).getY(), 0);
    }
    @Test public void clampsHorizontalMagnitudeNotAxesOrTotalVelocity() {
        Vector out = KnockbackMath.modify(new Vector(3, 5, 4), hit(HitType.EXPLOSION, false, false, false, 1, false), profile("limits.horizontal", 2, "limits.upward", 0.7));
        assertEquals(1.2, out.getX(), 1e-12); assertEquals(1.6, out.getZ(), 1e-12); assertEquals(0.7, out.getY(), 0);
    }
    @Test public void interpolatesWeakChargeAndAppliesMeleeModifiers() {
        var p = profile("modern.weak-hit-multiplier", 0.5, "modern.sprint-multiplier", 2, "modern.critical-multiplier", 2);
        assertEquals(3, KnockbackMath.modify(new Vector(1, 1, 1), hit(HitType.MACE, true, true, true, 0.5, false), p).getX(), 0);
        assertEquals(1, KnockbackMath.modify(new Vector(1, 1, 1), hit(HitType.ARROW, true, true, true, 0.5, false), p).getX(), 0);
    }
    @Test public void unknownChargeDoesNotInventWeakHit() {
        assertEquals(1, KnockbackMath.modify(new Vector(1, 0, 0), hit(HitType.MELEE, true, false, false, Double.NaN, false), profile("modern.weak-hit-multiplier", 0)).getX(), 0);
    }
    @Test public void disabledProfileLeavesNative() {
        assertEquals(new Vector(1, 1, 1), KnockbackMath.modify(new Vector(1, 1, 1), hit(HitType.MELEE, true, true, true, 0, false), profile("enabled", false, "horizontal.ground", 0)));
    }
    @Test public void rejectsInvalidNumbersAndFractionalTicks() {
        for (Object invalid : new Object[]{-1, 5, Double.NaN, Double.POSITIVE_INFINITY, "1.2"})
            assertThrows(IllegalArgumentException.class, () -> profile("horizontal.ground", invalid));
        assertThrows(IllegalArgumentException.class, () -> profile("rod.cast-interval-ticks", 1.5));
        assertThrows(IllegalArgumentException.class, () -> KnockbackMath.modify(new Vector(Double.NaN, 0, 0), hit(HitType.MELEE, true, false, false, 1, false), profile()));
    }
    @Test public void customEventDefensivelyCopiesAndValidates() {
        var context = hit(HitType.MELEE, true, false, false, 1, false);
        Vector delta = new Vector(1, 2, 3);
        var event = new KnockbackModifyEvent(context, "test", delta, delta);
        delta.setY(9); event.getImpulse().setY(7);
        assertEquals(2, event.getImpulse().getY(), 0); assertEquals(2, event.getOriginalImpulse().getY(), 0);
        assertThrows(IllegalArgumentException.class, () -> event.setImpulse(new Vector(0, Double.NaN, 0)));
    }
}

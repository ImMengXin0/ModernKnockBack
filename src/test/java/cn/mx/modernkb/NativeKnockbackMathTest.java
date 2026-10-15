package cn.mx.modernkb;

import cn.mx.modernkb.api.HitType;
import cn.mx.modernkb.combat.NativeKnockbackMath;
import cn.mx.modernkb.config.KnockbackProfile;
import org.bukkit.util.Vector;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeKnockbackMathTest {
    private static KnockbackProfile profile(Object... settings) {
        Object[] values = new Object[settings.length + 2];
        values[0] = "native.enabled"; values[1] = true;
        System.arraycopy(settings, 0, values, 2, settings.length);
        return KnockbackMathTest.profile(values);
    }
    /** Independent implementation of the source formula with an outward +X direction. */
    private static Vector delta(Vector before, double strength, boolean ground) {
        return new Vector(before.getX() / 2 + strength,
                ground ? Math.min(0.4, before.getY() / 2 + strength) : before.getY(), before.getZ() / 2).subtract(before);
    }
    private static NativeKnockbackMath.Result run(Vector before, double strength, boolean ground, KnockbackProfile profile) {
        return NativeKnockbackMath.calculate(delta(before, strength, ground), before, (float)strength,
                KnockbackMathTest.hit(HitType.MELEE, ground, false, false, 1, false), profile);
    }
    @Test public void neutralSettingsPreserveExactNativeDeltaAcrossMotionAndResistance() {
        for (boolean ground : new boolean[]{true, false}) for (double strength : new double[]{0.1, 0.2, 0.4, 0.7})
            for (Vector before : new Vector[]{new Vector(), new Vector(1, 0.3, -0.2), new Vector(-0.6, -0.5, 0.7)}) {
                var result = run(before, strength, ground, profile());
                assertTrue(result.fallbackReason(), result.nativeApplied());
                assertEquals(delta(before, strength, ground), result.impulse());
            }
    }
    @Test public void smallerPushDoesNotAccidentallyRetainMoreMomentum() {
        Vector before = new Vector(1, 0, 0);
        Vector after = before.clone().add(run(before, 0.4, true, profile("horizontal.ground", 0.9)).impulse());
        assertEquals(0.86, after.getX(), 1e-12); // 0.5 * 1 + 0.9 * 0.4; NOT 0.91 from scaling the whole delta.
    }
    @Test public void retentionAndPushCanBeAdjustedIndependentlyOnGroundAndInAir() {
        Vector before = new Vector(1, -0.2, -0.4);
        var p = profile("horizontal.ground", 0.8, "horizontal.air", 0.7,
                "native.horizontal-retention-ground", 0.3, "native.horizontal-retention-air", 0.6);
        Vector ground = before.clone().add(run(before, 0.4, true, p).impulse());
        Vector air = before.clone().add(run(before, 0.4, false, p).impulse());
        assertEquals(0.62, ground.getX(), 1e-12); assertEquals(-0.12, ground.getZ(), 1e-12);
        assertEquals(0.88, air.getX(), 1e-12); assertEquals(-0.24, air.getZ(), 1e-12);
        assertEquals(-0.2, air.getY(), 0);
    }
    @Test public void zeroHorizontalPushStillAppliesSelectedMomentumRetention() {
        Vector before = new Vector(1, 0, 0);
        assertEquals(0.5, before.clone().add(run(before, 0.4, true, profile("horizontal.ground", 0)).impulse()).getX(), 1e-12);
    }
    @Test public void groundVerticalCapIsFinalVelocityNotImpulse() {
        Vector before = new Vector(0, -0.6, 0);
        var result = run(before, 0.4, true, profile("vertical.ground", 2,
                "native.vertical-retention-ground", 0.5, "native.vertical-max-ground", 0.35));
        assertEquals(0.95, result.impulse().getY(), 1e-6);
        assertEquals(0.35, before.clone().add(result.impulse()).getY(), 1e-6);
    }
    @Test public void resistanceAlreadyAppliedIsNotAppliedTwice() {
        // Native base 0.4 with resistance 0.5 => supplied K=0.2, push multiplier 1.5 => 0.3.
        var out = run(new Vector(), 0.2, true, profile("horizontal.ground", 1.5, "vertical.ground", 1.5));
        assertEquals(0.3, out.impulse().getX(), 1e-6); assertEquals(0.3, out.impulse().getY(), 1e-6);
    }
    @Test public void airPreserveDoesNotInventLiftButAddModeCanSupplyIt() {
        Vector before = new Vector(0, -0.3, 0);
        assertEquals(0, run(before, 0.4, false, profile("vertical.air", 2)).impulse().getY(), 0);
        var result = run(before, 0.4, false, profile("native.air-vertical-mode", "add", "vertical.air", 2,
                "native.vertical-max-air", 0.25));
        assertEquals(0.25, before.clone().add(result.impulse()).getY(), 1e-6);
        before.setY(0.8);
        assertEquals(0, run(before, 0.4, false, profile("native.air-vertical-mode", "add")).impulse().getY(), 0);
    }
    @Test public void syncOwnsVerticalEvenInAirAddModeAndWithCaps() {
        Vector before = new Vector(0, -0.3, 0), original = delta(before, 0.4, false);
        var result = NativeKnockbackMath.calculate(original, before, 0.4,
                KnockbackMathTest.hit(HitType.MELEE, false, false, false, 1, true),
                profile("native.air-vertical-mode", "add", "limits.upward", 0.01));
        assertEquals(original.getY(), result.impulse().getY(), 0);
    }
    @Test public void upstreamChangesCauseFallbackInsteadOfOverwritingTheirFormula() {
        Vector before = new Vector(1, 0, 0);
        Vector original = delta(before, 0.4, true); original.setY(0.1);
        var result = NativeKnockbackMath.calculate(original, before, 0.4,
                KnockbackMathTest.hit(HitType.MELEE, true, false, false, 1, false), profile("horizontal.ground", 0.9));
        assertFalse(result.nativeApplied()); assertEquals("vertical-mismatch", result.fallbackReason());
        assertEquals(original.getX() * 0.9, result.impulse().getX(), 1e-12); assertEquals(0.1, result.impulse().getY(), 0);
        result = NativeKnockbackMath.calculate(original, before, 0.8,
                KnockbackMathTest.hit(HitType.MELEE, true, false, false, 1, false), profile());
        assertEquals("strength-mismatch", result.fallbackReason());
    }
    @Test public void zeroStrengthNeverReconstructsAHitAndNonFiniteInputsCannotPropagate() {
        var hit = KnockbackMathTest.hit(HitType.MELEE, true, false, false, 1, false);
        assertFalse(NativeKnockbackMath.calculate(new Vector(), new Vector(), 0, hit, profile()).nativeApplied());
        assertFalse(NativeKnockbackMath.calculate(new Vector(), new Vector(Double.NaN, 0, 0), 0.4, hit, profile()).nativeApplied());
        assertThrows(IllegalArgumentException.class, () -> NativeKnockbackMath.calculate(new Vector(0, Double.NaN, 0), new Vector(), 0.4, hit, profile()));
    }
    @Test public void modernMultipliersOnlyMultiplyTheNewPush() {
        Vector before = new Vector(1, 0, 0);
        var result = NativeKnockbackMath.calculate(delta(before, 0.4, true), before, 0.4,
                KnockbackMathTest.hit(HitType.MELEE, true, true, true, 0.5, false),
                profile("modern.sprint-multiplier", 2, "modern.critical-multiplier", 2, "modern.weak-hit-multiplier", 0.5));
        assertEquals(1.7, before.clone().add(result.impulse()).getX(), 1e-12); // 0.5 + 0.4 * 3
    }
    @Test public void sequentialComponentsEachUseTheirOwnCurrentVelocity() {
        Vector before = new Vector(0.8, 0, 0);
        var p = profile("horizontal.ground", 0.9);
        Vector first = before.clone().add(run(before, 0.4, true, p).impulse());
        Vector second = first.clone().add(run(first, 0.5, true, p).impulse());
        assertEquals(0.76, first.getX(), 1e-12); assertEquals(0.83, second.getX(), 1e-12);
    }
    @Test public void originalDeltaAndBeforeVectorsAreNotMutated() {
        Vector before = new Vector(1, 0, 0), original = delta(before, 0.4, true), copy = original.clone();
        NativeKnockbackMath.calculate(original, before, 0.4, KnockbackMathTest.hit(HitType.MELEE, true, false, false, 1, false),
                profile("horizontal.ground", 2));
        assertEquals(copy, original); assertEquals(new Vector(1, 0, 0), before);
    }
    @Test public void validatesNewFieldTypesAndRanges() {
        assertThrows(IllegalArgumentException.class, () -> profile("native.air-vertical-mode", "unknown"));
        assertThrows(IllegalArgumentException.class, () -> profile("native.air-vertical-mode", true));
        assertThrows(IllegalArgumentException.class, () -> profile("native.horizontal-retention-air", 1.1));
        assertThrows(IllegalArgumentException.class, () -> profile("native.vertical-max-ground", -0.1));
    }
}

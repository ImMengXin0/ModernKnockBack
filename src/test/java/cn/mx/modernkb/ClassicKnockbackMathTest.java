package cn.mx.modernkb;

import cn.mx.modernkb.api.*;
import cn.mx.modernkb.combat.*;
import org.bukkit.util.Vector;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClassicKnockbackMathTest {
    private ClassicKnockbackMath.Attack attack(boolean sprint, int enchantment, int punch) {
        return new ClassicKnockbackMath.Attack(new Vector(1, 0, 0), new Vector(1, 0, 0), sprint, enchantment, punch, 1);
    }
    @Test public void legacyVelocityReplacesMomentumInsteadOfAddingASecondHit() {
        Vector before = new Vector(1.7, -0.3, -0.5);
        var hit = KnockbackMathTest.hit(HitType.MELEE, true, false, false, 0, false);
        Vector impulse = ClassicKnockbackMath.impulse(before, hit, KnockbackMathTest.profile(), attack(false, 0, 0), 0, false);
        assertEquals(new Vector(0.4, 0.36075, 0), before.clone().add(impulse));
        assertEquals(new Vector(1.7, -0.3, -0.5), before);
    }
    @Test public void zeroAttackChargeStillHasFullClassicSprintAndEnchantmentPush() {
        var hit = KnockbackMathTest.hit(HitType.MELEE, true, true, false, 0, false);
        Vector result = ClassicKnockbackMath.impulse(new Vector(), hit, KnockbackMathTest.profile(), attack(true, 2, 0), 0, false);
        assertEquals(1.9, result.getX(), 1e-12); assertEquals(0.46075, result.getY(), 1e-12);
    }
    @Test public void airHeightLimitDoesNotAffectRodAndAirUsesLegacyLift() {
        var melee = KnockbackMathTest.hit(HitType.MELEE, false, false, false, 1, false);
        assertEquals(0.24775, ClassicKnockbackMath.impulse(new Vector(), melee, KnockbackMathTest.profile(), attack(false, 0, 0), 0, false).getY(), 0);
        assertEquals(0, ClassicKnockbackMath.impulse(new Vector(), melee, KnockbackMathTest.profile("classic.y-limit-enabled", true), attack(false, 0, 0), 0, true).getY(), 0);
        var rod = KnockbackMathTest.hit(HitType.FISHING_ROD, false, true, false, 0, false);
        assertEquals(new Vector(0.4, 0.24775, 0), ClassicKnockbackMath.impulse(new Vector(), rod, KnockbackMathTest.profile(), attack(true, 3, 0), 0, true));
    }
    @Test public void resistanceScalesNewPushOnceAndRetentionCanBeConfigured() {
        var profile = KnockbackMathTest.profile("classic.horizontal-retention", 0.5);
        Vector before = new Vector(1, 0, 0);
        var result = ClassicKnockbackMath.impulse(before, KnockbackMathTest.hit(HitType.MELEE, true, false, false, 1, false), profile, attack(false, 0, 0), 0.5, false);
        assertEquals(0.7, before.clone().add(result).getX(), 1e-12);
        assertEquals(0.180375, result.getY(), 1e-12);
    }
    @Test public void arrowPunchIsIncludedOnceAndDoesNotBorrowSprintBonus() {
        var hit = KnockbackMathTest.hit(HitType.ARROW, true, true, false, 0, false);
        assertEquals(1.6, ClassicKnockbackMath.impulse(new Vector(), hit, KnockbackMathTest.profile(), attack(true, 2, 2), 0, false).getX(), 1e-12);
    }
    @Test public void coincidentPositionUsesFiniteFacingAndDoesNotInventVerticalDirection() {
        var attack = new ClassicKnockbackMath.Attack(new Vector(), new Vector(0, 0, -1), false, 0, 0, 1);
        assertEquals(new Vector(0, 0.36075, -0.4), ClassicKnockbackMath.impulse(new Vector(),
                KnockbackMathTest.hit(HitType.MELEE, true, false, false, 1, false), KnockbackMathTest.profile(), attack, 0, false));
    }
    @Test public void sprintStrengthDoesNotDependOnAttackerYaw() {
        var hit = KnockbackMathTest.hit(HitType.MELEE, true, true, false, 1, false);
        for (Vector facing : new Vector[]{new Vector(1, 0, 0), new Vector(0, 0, 1), new Vector(-1, 0, 0)}) {
            var attack = new ClassicKnockbackMath.Attack(new Vector(1, 0, 0), facing, true, 0, 0, 1);
            Vector result = ClassicKnockbackMath.impulse(new Vector(), hit, KnockbackMathTest.profile(), attack, 0, false);
            assertEquals(new Vector(0.9, 0.46075, 0), result);
        }
    }
    @Test public void modernMultipliersAndDeltaLimitsCannotChangeClassicFinalSpeed() {
        var profile = KnockbackMathTest.profile("horizontal.ground", 2, "vertical.ground", 3,
                "modern.sprint-multiplier", 0.2, "modern.critical-multiplier", 0.2, "modern.weak-hit-multiplier", 0.2,
                "limits.horizontal", 0.1, "limits.upward", 0.1, "limits.downward", 0.1);
        var hit = KnockbackMathTest.hit(HitType.MELEE, true, false, true, 0, false);
        for (Vector before : new Vector[]{new Vector(), new Vector(2, -0.7, -1), new Vector(-2, 0.9, 1)}) {
            Vector after = before.clone().add(ClassicKnockbackMath.impulse(before, hit, profile, attack(false, 0, 0), 0, false));
            assertEquals(0.4, after.getX(), 1e-12);
            assertEquals(0.36075, after.getY(), 1e-12);
            assertEquals(0, after.getZ(), 1e-12);
        }
    }
    @Test public void defaultAirLiftDoesNotDependOnPastComboHeight() {
        var hit = KnockbackMathTest.hit(HitType.MELEE, false, false, false, 1, false);
        assertEquals(0.24775, ClassicKnockbackMath.impulse(new Vector(), hit, KnockbackMathTest.profile(), attack(false, 0, 0), 0, true).getY(), 0);
    }
    @Test public void repeatedPlayerBotArrowAndRodHitsHaveConsistentFinalSpeedAcrossMomentumAndYaw() {
        var random = new java.util.Random(1821);
        for (HitType type : new HitType[]{HitType.MELEE, HitType.ARROW, HitType.FISHING_ROD})
            for (CombatantKind kind : new CombatantKind[]{CombatantKind.PLAYER, CombatantKind.BOT})
                for (boolean ground : new boolean[]{true, false})
                    for (int index = 0; index < 32; index++) {
                        double yaw = random.nextDouble() * Math.PI * 2;
                        Vector before = new Vector(random.nextDouble() * 4 - 2, random.nextDouble() * 2 - 1, random.nextDouble() * 4 - 2);
                        var hit = new HitContext(null, null, null, type, CombatantKind.PLAYER, kind, "ENTITY_ATTACK",
                                ground, true, index % 2 == 0, random.nextDouble(), false);
                        var attack = new ClassicKnockbackMath.Attack(new Vector(3, 0, 4), new Vector(Math.cos(yaw), 0, Math.sin(yaw)), true, 0, 0, 1);
                        Vector after = before.clone().add(ClassicKnockbackMath.impulse(before, hit, KnockbackMathTest.profile(), attack, 0, index % 2 == 0));
                        double expectedH = type.melee() ? 0.9 : 0.4;
                        assertEquals(expectedH * 0.6, after.getX(), 1e-12);
                        assertEquals(expectedH * 0.8, after.getZ(), 1e-12);
                        assertEquals((ground ? 0.36075 : 0.24775) + (type.melee() ? 0.1 : 0), after.getY(), 1e-12);
                    }
    }
    @Test public void jumpResetRetainsHalfOfExistingPlayerMotionWithoutChangingTheConfiguredPush() {
        var hit = KnockbackMathTest.hit(HitType.MELEE, false, false, false, 1, false);
        Vector before = new Vector(0.8, 0.42, -0.2);
        Vector ordinary = before.clone().add(ClassicKnockbackMath.impulse(before, hit, KnockbackMathTest.profile(), attack(false, 0, 0), 0, false));
        Vector reset = before.clone().add(ClassicKnockbackMath.impulse(before, hit, KnockbackMathTest.profile(), attack(false, 0, 0), 0, false, true));
        assertEquals(new Vector(0.4, 0.24775, 0), ordinary);
        assertEquals(new Vector(0.8, 0.45775, -0.1), reset);
    }
}

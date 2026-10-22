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
    @Test public void mirroredStrafesHaveEqualStrengthWhenTheWholeFightRotates() {
        var profile = KnockbackMathTest.profile();
        var hit = KnockbackMathTest.hit(HitType.MELEE, true, true, false, 1, false);
        for (double angle : new double[]{0, 0.3, Math.PI / 2, Math.PI, 4.7})
            for (int side : new int[]{-1, 1}) {
                // Mirrored positions around the attacker's facing, rotated together in world space.
                Vector direction = rotate(new Vector(3, 0, side * 4), angle);
                Vector facing = rotate(new Vector(1, 0, 0), angle);
                Vector before = rotate(new Vector(-0.7, 0.1, side * 0.8), angle);
                var attack = new ClassicKnockbackMath.Attack(direction, facing, true, 0, 0, 1);
                Vector after = before.clone().add(ClassicKnockbackMath.impulse(before, hit,
                        profile, attack, 0, false));
                Vector local = rotate(after, -angle);
                assertEquals(0.54, local.getX(), 1e-12);
                assertEquals(side * 0.72, local.getZ(), 1e-12);
                assertEquals(0.46075, local.getY(), 1e-12);
                assertEquals(0.9, Math.hypot(after.getX(), after.getZ()), 1e-12);
            }
    }
    @Test public void zeroRetentionCannotAccumulateSidewaysDriftDuringAChangingDirectionCombo() {
        var profile = KnockbackMathTest.profile("classic.horizontal-retention", 0.0,
                "classic.vertical-retention", 0.0);
        Vector before = new Vector(1.1, 0.42, -0.9);
        for (int index = 0; index < 24; index++) {
            double angle = index * 0.37;
            Vector forward = rotate(new Vector(1, 0, 0), angle);
            Vector sideways = rotate(new Vector(0, 0, 1), angle);
            boolean sprint = index % 3 != 0, ground = index % 4 == 0;
            var hit = KnockbackMathTest.hit(HitType.MELEE, ground, sprint, index % 2 == 0, 0, false);
            var attack = new ClassicKnockbackMath.Attack(forward.clone().multiply(2), sideways,
                    sprint, 0, 0, 1);
            Vector previous = before.clone();
            Vector after = before.clone().add(ClassicKnockbackMath.impulse(before, hit,
                    profile, attack, 0, false));
            assertEquals(sprint ? 0.9 : 0.4, after.dot(forward), 1e-12);
            assertEquals(0, after.dot(sideways), 1e-12);
            assertEquals((ground ? 0.36075 : 0.24775) + (sprint ? 0.1 : 0), after.getY(), 1e-12);
            assertEquals(previous, before);
            // Between accepted hits, the victim strafes and gravity changes its motion.
            before = after.clone().add(sideways.clone().multiply(index % 2 == 0 ? 0.3 : -0.3));
            before.setY(before.getY() - 0.18);
        }
    }
    @Test public void jumpResetRetainsExactlyHalfOfMirroredStrafeMomentumAtEveryHeading() {
        var profile = KnockbackMathTest.profile();
        var hit = KnockbackMathTest.hit(HitType.MELEE, false, false, false, 1, false);
        for (double angle : new double[]{0, 0.3, Math.PI / 2, Math.PI, 4.7})
            for (double forwardSpeed : new double[]{-0.8, 0, 0.8})
                for (int side : new int[]{-1, 1}) {
                    Vector before = rotate(new Vector(forwardSpeed, 0.42, side * 0.2), angle);
                    Vector direction = rotate(new Vector(1, 0, 0), angle);
                    var attack = new ClassicKnockbackMath.Attack(direction, direction.clone(),
                            false, 0, 0, 1);
                    Vector ordinary = before.clone().add(ClassicKnockbackMath.impulse(before, hit,
                            profile, attack, 0, false, false));
                    Vector reset = before.clone().add(ClassicKnockbackMath.impulse(before, hit,
                            profile, attack, 0, false, true));
                    Vector retained = reset.clone().subtract(ordinary);
                    assertEquals(before.getX() * 0.5, retained.getX(), 1e-12);
                    assertEquals(before.getY() * 0.5, retained.getY(), 1e-12);
                    assertEquals(before.getZ() * 0.5, retained.getZ(), 1e-12);
                    Vector local = rotate(reset, -angle);
                    assertEquals(0.4 + forwardSpeed * 0.5, local.getX(), 1e-12);
                    assertEquals(side * 0.1, local.getZ(), 1e-12);
                    assertEquals(0.45775, local.getY(), 1e-12);
                }
    }
    private static Vector rotate(Vector vector, double angle) {
        double cosine = Math.cos(angle), sine = Math.sin(angle);
        return new Vector(vector.getX() * cosine - vector.getZ() * sine, vector.getY(),
                vector.getX() * sine + vector.getZ() * cosine);
    }
    @Test public void overlappingMeleeDirectionDependsOnYawNotTinyOffsetsHeightOrCriticalFlag() {
        for (boolean critical : new boolean[]{false, true})
            for (boolean grounded : new boolean[]{false, true})
                for (double height : new double[]{0, -1.2, 1.2})
                    for (double offset : new double[]{-0.001, 0, 0.001}) {
                        var hit = KnockbackMathTest.hit(HitType.MELEE, grounded, true, critical, 1, false);
                        var attack = new ClassicKnockbackMath.Attack(new Vector(offset, height, -offset),
                                new Vector(0, 0, 1), true, 1, 0, 1);
                        var result = ClassicKnockbackMath.impulse(new Vector(), hit, KnockbackMathTest.profile(), attack, 0, false);
                        assertEquals(0, result.getX(), 1e-12);
                        assertEquals(1.4, result.getZ(), 1e-12);
                        assertEquals((grounded ? 0.36075 : 0.24775) + 0.1, result.getY(), 1e-12);
                    }
    }
    @Test public void resolvedMeleeOutsideCoincidentToleranceStillPushesAwayFromAttacker() {
        var hit = KnockbackMathTest.hit(HitType.MELEE, true, false, true, 1, false);
        for (Vector displacement : new Vector[]{new Vector(0.004, -1.2, 0), new Vector(2, -1.2, 0)}) {
            var attack = new ClassicKnockbackMath.Attack(displacement, new Vector(0, 0, -1), false, 0, 0, 1);
            assertEquals(new Vector(0.4, 0.36075, 0), ClassicKnockbackMath.impulse(new Vector(), hit, KnockbackMathTest.profile(), attack, 0, false));
        }
    }
    @Test public void slowRodAndArrowKeepFlightDirectionInsteadOfBorrowingMeleeTolerance() {
        for (HitType type : new HitType[]{HitType.FISHING_ROD, HitType.ARROW, HitType.CROSSBOW}) {
            var hit = KnockbackMathTest.hit(type, true, false, false, 1, false);
            var attack = new ClassicKnockbackMath.Attack(new Vector(-0.001, -0.1, 0), new Vector(0, 0, 1), false, 0, 0, 1);
            assertEquals(new Vector(-0.4, 0.36075, 0), ClassicKnockbackMath.impulse(new Vector(), hit, KnockbackMathTest.profile(), attack, 0, false));
        }
    }
}

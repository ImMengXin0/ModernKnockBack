package cn.mx.modernkb.combat;

import cn.mx.modernkb.api.*;
import cn.mx.modernkb.compat.LeafVelocityDispatch;
import cn.mx.modernkb.config.KnockbackProfile;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.util.Vector;

/** Move plugin-owned first-use work off the first real hit, without touching a live entity. */
public final class CombatWarmup {
    private CombatWarmup() {}

    public static void prepare(KnockbackProfile profile) {
        Objects.requireNonNull(profile);
        for (Class<?> root : List.of(ClassicCombat.class, CombatTracker.class, KnockbackListener.class,
                ClassicClickDensity.class, ClassicJumpTracker.class, HitClassifier.class,
                ClassicKnockbackMath.class, KnockbackMath.class, NativeKnockbackMath.class,
                CombatItems.class, HitContext.class, HitType.class, CombatantKind.class, KnockbackProfileEvent.class,
                KnockbackModifyEvent.class, LeafVelocityDispatch.class, RemoveEntryTask.class)) initializeTree(root);

        // Bounded, pure calculations link switch/lambda call sites as well as loading their classes.
        // The supplied profile is immutable. Local samples never enter a live combat tracker.
        UUID id = new UUID(0, 0);
        ClassicClickDensity density = new ClassicClickDensity();
        density.record(id, 0, profile);
        density.record(id, 0, profile);
        density.reduction(id, 0, profile);
        density.clear();
        for (boolean grounded : new boolean[]{true, false}) {
            for (HitType type : new HitType[]{HitType.MELEE, HitType.FISHING_ROD}) {
                HitContext hit = new HitContext(id, id, id, type, CombatantKind.PLAYER,
                        CombatantKind.PLAYER, "ENTITY_ATTACK", grounded, false, false, 1, false);
                Vector before = new Vector(), impulse = new Vector(0.4, grounded ? 0.4 : 0, 0);
                var attack = new ClassicKnockbackMath.Attack(new Vector(1, 0, 0), new Vector(1, 0, 0), false, 0, 0, 1);
                ClassicKnockbackMath.impulse(before, hit, profile, attack, 0, false, false, 0);
                KnockbackMath.modify(impulse, hit, profile);
                NativeKnockbackMath.calculate(impulse, before, 0.4, hit, profile);
                HitClassifier.cause("ENTITY_ATTACK", HitClassifier.weapon("IRON_SWORD"));
            }
        }
    }

    static void initializeTree(Class<?> type) {
        try {
            // A class literal alone does not initialize static compatibility caches/handler lists.
            Class.forName(type.getName(), true, type.getClassLoader());
            for (Class<?> nested : type.getDeclaredClasses()) initializeTree(nested);
        } catch (ClassNotFoundException impossible) {
            throw new IllegalStateException("Cannot initialize combat class " + type.getName(), impossible);
        }
    }
}

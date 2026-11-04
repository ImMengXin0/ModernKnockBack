package cn.mx.modernkb.api;

import java.util.UUID;

/** Immutable, safe to retain across regions. Unknown charge is NaN; no live attacker reference. */
public record HitContext(UUID victim, UUID attacker, UUID source, HitType type,
                         CombatantKind attackerKind, CombatantKind victimKind,
                         String nativeCause, boolean grounded, boolean sprinting,
                         boolean critical, double attackCharge, boolean syncOwnsVertical, String weapon) {
    public HitContext(UUID victim, UUID attacker, UUID source, HitType type,
                      CombatantKind attackerKind, CombatantKind victimKind, String nativeCause,
                      boolean grounded, boolean sprinting, boolean critical, double attackCharge, boolean syncOwnsVertical) {
        this(victim, attacker, source, type, attackerKind, victimKind, nativeCause, grounded, sprinting,
                critical, attackCharge, syncOwnsVertical, "");
    }
    public HitContext withSyncVertical(boolean preserve) {
        return new HitContext(victim, attacker, source, type, attackerKind, victimKind, nativeCause,
                grounded, sprinting, critical, attackCharge, preserve, weapon);
    }
}

package cn.mx.modernkb.api;

import java.util.UUID;

/** Immutable, safe to retain across regions. Unknown charge is NaN; no live attacker reference. */
public record HitContext(UUID victim, UUID attacker, UUID source, HitType type,
                         CombatantKind attackerKind, CombatantKind victimKind,
                         String nativeCause, boolean grounded, boolean sprinting,
                         boolean critical, double attackCharge, boolean syncOwnsVertical) {}

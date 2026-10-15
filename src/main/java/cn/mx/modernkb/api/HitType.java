package cn.mx.modernkb.api;

import java.util.Locale;

public enum HitType {
    MELEE, ARROW, CROSSBOW, TRIDENT, MACE, SPEAR, SWEEP, SHIELD_BLOCK,
    FISHING_ROD, WIND_CHARGE, EXPLOSION, CRYSTAL, TNT,
    REGULARITY, GRIM_REAPER, OTHER_PROJECTILE, DAMAGE, UNKNOWN;

    public String key() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }
    public boolean melee() {
        return this == MELEE || this == MACE || this == SPEAR || this == REGULARITY || this == GRIM_REAPER;
    }
}

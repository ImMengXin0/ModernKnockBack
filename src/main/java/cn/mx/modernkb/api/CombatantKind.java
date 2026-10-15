package cn.mx.modernkb.api;

import java.util.Locale;

public enum CombatantKind {
    PLAYER, BOT, OTHER, UNKNOWN;
    public String key() { return name().toLowerCase(Locale.ROOT); }
}

package cn.mx.modernkb.api;

import java.util.Locale;

public enum CombatMode {
    CLASSIC, MODERN;
    public String key() { return name().toLowerCase(Locale.ROOT); }
    public static CombatMode parse(String value) {
        if (value == null) throw new IllegalArgumentException("Mode must be classic or modern");
        try { return valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Mode must be classic or modern"); }
    }
}

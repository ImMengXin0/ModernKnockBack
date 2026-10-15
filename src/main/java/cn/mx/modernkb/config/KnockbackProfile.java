package cn.mx.modernkb.config;

import org.bukkit.configuration.ConfigurationSection;

public record KnockbackProfile(boolean enabled, double groundHorizontal, double airHorizontal,
                               double groundVertical, double airVertical, double sprintMultiplier,
                               double criticalMultiplier, double weakMultiplier, double maxHorizontal,
                               double maxUpward, double maxDownward, Rod rod, Native nativeSettings, Classic classic) {
    public record Classic(double horizontalGround, double horizontalAir, double verticalGround, double verticalAir,
                          double horizontalRetention, double verticalRetention, double sprintHorizontal, double sprintVertical,
                          double enchantmentHorizontal, double verticalMax, boolean respectResistance, boolean yLimit,
                          double maxHeight, double verticalAfterLimit) {
        public static Classic defaults() { return new Classic(0.4, 0.4, 0.36075, 0.24775, 0, 0, 0.5, 0.1, 0.5, 0.5, true, false, 0.675, 0); }
    }
    /** Retain the 1.1 constructor for compiled integrations. */
    public KnockbackProfile(boolean enabled, double groundHorizontal, double airHorizontal, double groundVertical, double airVertical,
                            double sprintMultiplier, double criticalMultiplier, double weakMultiplier, double maxHorizontal,
                            double maxUpward, double maxDownward, Rod rod, Native nativeSettings) {
        this(enabled, groundHorizontal, airHorizontal, groundVertical, airVertical, sprintMultiplier, criticalMultiplier,
                weakMultiplier, maxHorizontal, maxUpward, maxDownward, rod, nativeSettings, Classic.defaults());
    }
    public record Native(boolean enabled, double groundHorizontalRetention, double airHorizontalRetention,
                         double groundVerticalRetention, double groundVerticalMax,
                         boolean addAirVertical, double airVerticalMax) {
        public static Native defaults() { return new Native(false, 0.5, 0.5, 0.5, 0.4, false, 0.4); }
    }
    /** Retain the 1.0 constructor for integrations using the configuration model. */
    public KnockbackProfile(boolean enabled, double groundHorizontal, double airHorizontal,
                            double groundVertical, double airVertical, double sprintMultiplier,
                            double criticalMultiplier, double weakMultiplier, double maxHorizontal,
                            double maxUpward, double maxDownward, Rod rod) {
        this(enabled, groundHorizontal, airHorizontal, groundVertical, airVertical, sprintMultiplier,
                criticalMultiplier, weakMultiplier, maxHorizontal, maxUpward, maxDownward, rod, Native.defaults());
    }
    public record Rod(boolean enabled, int castTicks, int recastTicks, double horizontalSpeed,
                      double verticalSpeed, double maxDistance, int lifetimeTicks, double impactStrength) {}

    public static KnockbackProfile read(ConfigurationSection yaml) {
        double classicHorizontal = number(yaml, "classic.horizontal", 0.4, 0, 4);
        String airMode = "preserve";
        if (yaml.contains("native.air-vertical-mode")) {
            Object value = yaml.get("native.air-vertical-mode");
            if (!(value instanceof String text) || !(text.equals("preserve") || text.equals("add")))
                throw new IllegalArgumentException("native.air-vertical-mode must be preserve or add");
            airMode = text;
        }
        return new KnockbackProfile(bool(yaml,"enabled",true),
                number(yaml,"horizontal.ground",1,0,4), number(yaml,"horizontal.air",1,0,4),
                number(yaml,"vertical.ground",1,0,4), number(yaml,"vertical.air",1,0,4),
                number(yaml,"modern.sprint-multiplier",1,0,4), number(yaml,"modern.critical-multiplier",1,0,4),
                number(yaml,"modern.weak-hit-multiplier",1,0,4), number(yaml,"limits.horizontal",0,0,10),
                number(yaml,"limits.upward",0,0,10), number(yaml,"limits.downward",0,0,10),
                new Rod(bool(yaml,"rod.enabled",true), integer(yaml,"rod.cast-interval-ticks",0,1200),
                        integer(yaml,"rod.recast-delay-ticks",0,1200), number(yaml,"rod.horizontal-speed-multiplier",1,0,4),
                        number(yaml,"rod.vertical-speed-multiplier",1,0,4), number(yaml,"rod.max-distance",0,0,32),
                        integer(yaml,"rod.max-lifetime-ticks",0,12000), number(yaml,"rod.impact-strength",0,0,2)),
                new Native(bool(yaml,"native.enabled",false),
                        number(yaml,"native.horizontal-retention-ground",0.5,0,1),
                        number(yaml,"native.horizontal-retention-air",0.5,0,1),
                        number(yaml,"native.vertical-retention-ground",0.5,0,1),
                        number(yaml,"native.vertical-max-ground",0.4,0,4), airMode.equals("add"),
                        number(yaml,"native.vertical-max-air",0.4,0,4)),
                new Classic(number(yaml,"classic.horizontal-ground",classicHorizontal,0,4), number(yaml,"classic.horizontal-air",classicHorizontal,0,4),
                        number(yaml,"classic.vertical-ground",0.36075,0,4), number(yaml,"classic.vertical-air",0.24775,0,4),
                        number(yaml,"classic.horizontal-retention",0,0,1), number(yaml,"classic.vertical-retention",0,0,1),
                        number(yaml,"classic.sprint-horizontal",0.5,0,4), number(yaml,"classic.sprint-vertical",0.1,0,4),
                        number(yaml,"classic.enchantment-horizontal",0.5,0,4), number(yaml,"classic.vertical-max",0.5,0,4),
                        bool(yaml,"classic.respect-resistance",true), bool(yaml,"classic.y-limit-enabled",false),
                        number(yaml,"classic.max-height",0.675,0,8), number(yaml,"classic.vertical-after-limit",0,-4,0)));
    }
    public static boolean bool(ConfigurationSection c, String key, boolean fallback) {
        if (!c.contains(key)) return fallback;
        if (!(c.get(key) instanceof Boolean value)) throw new IllegalArgumentException("Expected boolean: " + key);
        return value;
    }
    private static int integer(ConfigurationSection c, String key, int fallback, int max) {
        double value = number(c,key,fallback,0,max);
        if (value != Math.rint(value)) throw new IllegalArgumentException("Expected integer ticks: " + key);
        return (int)value;
    }
    private static double number(ConfigurationSection c, String key, double fallback, double min, double max) {
        Object raw = c.get(key);
        if (raw == null) return fallback;
        if (!(raw instanceof Number n)) throw new IllegalArgumentException("Expected number: " + key);
        double v = n.doubleValue();
        if (!Double.isFinite(v) || v < min || v > max) throw new IllegalArgumentException(key + " must be within " + min + ".." + max);
        return v;
    }
}

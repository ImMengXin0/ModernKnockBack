package cn.mx.modernkb.config;

import java.nio.file.Path;
import java.util.Set;

/** Attack charge and hurt immunity are deliberately separate settings. */
public record ClassicSettings(int hitIntervalTicks, int rodHitIntervalTicks, boolean disableSweep,
                              boolean protectVelocityFromSync, boolean resetSprintOnHit,
                              boolean jumpResetEnabled, int jumpResetWindowTicks, int rodMeleeIntervalTicks) {
    /** Compatibility constructor for integrations using the 1.2.3 settings shape. */
    public ClassicSettings(int hitIntervalTicks, int rodHitIntervalTicks, boolean disableSweep,
                           boolean protectVelocityFromSync, boolean resetSprintOnHit,
                           boolean jumpResetEnabled, int jumpResetWindowTicks) {
        this(hitIntervalTicks, rodHitIntervalTicks, disableSweep, protectVelocityFromSync,
                resetSprintOnHit, jumpResetEnabled, jumpResetWindowTicks, rodHitIntervalTicks);
    }
    /** Compatibility constructor for integrations using the 1.2.0 settings shape. */
    public ClassicSettings(int hitIntervalTicks, int rodHitIntervalTicks, boolean disableSweep,
                           boolean protectVelocityFromSync) {
        this(hitIntervalTicks, rodHitIntervalTicks, disableSweep, protectVelocityFromSync, false, true, 2);
    }
    /** Same semantics as legacy KnockbackManager stop_sprint; the record accessor remains API compatible. */
    public boolean stopSprint() { return resetSprintOnHit; }
    /** A sword must not interrupt the rod's own accepted hit window. */
    public int effectiveRodMeleeTicks() { return Math.max(effectiveRodHitTicks(), rodMeleeIntervalTicks); }
    /** Rod damage(0) shares the classic hurt window; old short rod settings cannot shorten it. */
    public int effectiveRodHitTicks() { return Math.max(hitIntervalTicks, rodHitIntervalTicks); }
    public static ClassicSettings defaults() { return new ClassicSettings(10, 10, true, true, false, true, 2); }
    public static ClassicSettings load(Path file) {
        var yaml = YamlFiles.load(file);
        return read(yaml);
    }
    static ClassicSettings read(org.bukkit.configuration.file.YamlConfiguration yaml) {
        Set<String> allowed = Set.of("hit-interval-ticks", "rod-hit-interval-ticks", "disable-sweep", "protect-velocity-from-sync",
                "stop_sprint", "reset-sprint-on-hit", "jump-reset-enabled", "jump-reset-window-ticks", "rod-melee-interval-ticks");
        for (String key : yaml.getKeys(false)) if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown classic combat field: " + key);
        boolean stopSprint = KnockbackProfile.bool(yaml, "stop_sprint", false);
        boolean legacyReset = KnockbackProfile.bool(yaml, "reset-sprint-on-hit", false);
        if (yaml.contains("stop_sprint") && yaml.contains("reset-sprint-on-hit") && stopSprint != legacyReset)
            throw new IllegalArgumentException("Conflicting stop_sprint and reset-sprint-on-hit; keep only stop_sprint");
        if (!yaml.contains("stop_sprint")) stopSprint = legacyReset;
        return new ClassicSettings(ticks(yaml.get("hit-interval-ticks"), 10, "hit-interval-ticks"),
                ticks(yaml.get("rod-hit-interval-ticks"), 10, "rod-hit-interval-ticks"),
                KnockbackProfile.bool(yaml, "disable-sweep", true), KnockbackProfile.bool(yaml, "protect-velocity-from-sync", true),
                stopSprint, KnockbackProfile.bool(yaml, "jump-reset-enabled", true),
                ticks(yaml.get("jump-reset-window-ticks"), 2, "jump-reset-window-ticks"),
                ticks(yaml.get("rod-melee-interval-ticks"), 10, "rod-melee-interval-ticks"));
    }
    private static int ticks(Object raw, int fallback, String key) {
        if (raw == null) return fallback;
        if (!(raw instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() != Math.rint(n.doubleValue())
                || n.doubleValue() < 1 || n.doubleValue() > 40) throw new IllegalArgumentException(key + " must be integer 1..40");
        return n.intValue();
    }
}

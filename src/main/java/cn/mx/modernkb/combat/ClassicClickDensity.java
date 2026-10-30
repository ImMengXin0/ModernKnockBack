package cn.mx.modernkb.combat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Short, saturated server-tick buckets; never accumulates a persistent KB multiplier. */
final class ClassicClickDensity {
    static final double MAX_REDUCTION = 0.20;
    private static final double REDUCTION_PER_EXTRA_CLICK = 0.05;
    private static final int MAX_CLICKS_PER_TICK = 5;
    private record Sample(Object configuration, int tick, int current, int previous) {}
    private final Map<UUID, Sample> samples = new ConcurrentHashMap<>();

    void record(UUID player, int tick, Object configuration) {
        samples.compute(player, (id, old) -> {
            if (old == null || old.configuration != configuration) return new Sample(configuration, tick, 1, 0);
            long age = Integer.toUnsignedLong(tick - old.tick);
            if (age == 0) return new Sample(configuration, tick, Math.min(MAX_CLICKS_PER_TICK, old.current + 1), old.previous);
            return new Sample(configuration, tick, 1, age == 1 ? old.current : 0);
        });
    }

    double reduction(UUID player, int tick, Object configuration) {
        Sample sample = samples.get(player);
        if (sample == null) return 0;
        long age = Integer.toUnsignedLong(tick - sample.tick);
        if (sample.configuration != configuration || age > 1) {
            samples.remove(player, sample);
            return 0;
        }
        // Keep a burst across one tick boundary, without turning two ordinary clicks into a burst.
        int count = age == 0 ? Math.max(sample.current, sample.previous) : sample.current;
        return Math.min(MAX_REDUCTION, Math.max(0, count - 1) * REDUCTION_PER_EXTRA_CLICK);
    }

    void clear(UUID player) { samples.remove(player); }
    void clear() { samples.clear(); }
}

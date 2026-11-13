package cn.mx.modernkb.profiler;

import cn.mx.modernkb.config.Settings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Bounded, thread-safe session data. No Bukkit entity, event or scheduler is retained here. */
public final class ProfilerRecording {
    private static final int MAX_CONFIGURATIONS = 64;
    /** A capability belonging to exactly one recording; callers may safely keep it across region callbacks. */
    public static final class Ticket {
        private final ProfilerRecording owner;
        private final int id;
        private final long timeMs;
        private final Map<String, Object> fields;
        private boolean finished;

        private Ticket(ProfilerRecording owner, int id, long timeMs, Map<String, Object> fields) {
            this.owner = owner;
            this.id = id;
            this.timeMs = timeMs;
            this.fields = fields;
        }

        public int id() { return id; }
        public long timeMs() { return timeMs; }
    }

    private final String id;
    private final String startedBy;
    private final String startedMode;
    private final String startedAt;
    private final long startedNanos;
    private final long durationNanos;
    private final int maxSamples;
    private final long maxDurationSeconds;
    private final Map<String, Object> environment;
    private List<Ticket> samples = new ArrayList<>();
    private List<Map<String, Object>> configurations = new ArrayList<>();
    private final IdentityHashMap<Settings, Integer> configurationIds = new IdentityHashMap<>();
    private long observed;
    private long omitted;
    private long omittedConfigurations;
    private int size;
    private int completedCount;
    private boolean sealed;
    private String stoppedAt;
    private long stoppedNanos;
    private CompletableFuture<Map<String, Object>> stoppedFuture;

    private record Detached(List<Ticket> samples, List<Map<String, Object>> configurations,
                            long observed, long omitted, long omittedConfigurations, int completedCount,
                            String stoppedAt, long durationMs) {}

    public ProfilerRecording(String id, String startedBy, String startedMode, Map<String, Object> environment,
                             int maxSamples, long maxDurationSeconds) {
        if (maxSamples <= 0) throw new IllegalArgumentException("maxSamples must be positive");
        if (maxDurationSeconds <= 0 || maxDurationSeconds > Long.MAX_VALUE / 1_000_000_000L)
            throw new IllegalArgumentException("maxDurationSeconds must be positive and fit nanoseconds");
        this.id = Objects.requireNonNull(id);
        this.startedBy = Objects.requireNonNull(startedBy);
        this.startedMode = Objects.requireNonNull(startedMode);
        this.environment = ProfilerJson.freezeMap(Objects.requireNonNull(environment));
        this.maxSamples = maxSamples;
        this.maxDurationSeconds = maxDurationSeconds;
        this.durationNanos = TimeUnit.SECONDS.toNanos(maxDurationSeconds);
        this.startedAt = Instant.now().toString();
        this.startedNanos = System.nanoTime();
    }

    public boolean expired() { return System.nanoTime() - startedNanos >= durationNanos; }
    public long startedNanos() { return startedNanos; }

    /** The same immutable Settings instance is captured once; reloads produce a distinct snapshot. */
    public synchronized int configuration(Settings settings) {
        Objects.requireNonNull(settings);
        if (sealed || expired()) return -1;
        Integer existing = configurationIds.get(settings);
        if (existing != null) return existing;
        // Configuration reloads must not turn a bounded profiler into an unbounded collector.
        if (configurations.size() >= Math.min(MAX_CONFIGURATIONS, (long) maxSamples + 1L)) {
            omittedConfigurations++;
            return -1;
        }
        int configurationId = configurations.size() + 1;
        Map<String, Object> values = ProfilerConfiguration.snapshot(settings);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", configurationId);
        snapshot.put("timeMs", elapsedMs());
        snapshot.put("mode", settings.mode().key());
        snapshot.put("values", values);
        snapshot.put("descriptions", ProfilerConfiguration.descriptions(values));
        configurations.add(ProfilerJson.freezeMap(snapshot));
        configurationIds.put(settings, configurationId);
        return configurationId;
    }

    /** Count all observed attempts while active, even if the retention limit has been reached. */
    public synchronized Ticket begin(Map<String, Object> fields) {
        if (sealed) return null;
        observed++;
        if (expired() || samples.size() >= maxSamples) {
            omitted++;
            return null;
        }
        Map<String, Object> snapshot;
        try {
            snapshot = fields(fields);
        } catch (RuntimeException invalidFields) {
            omitted++;
            throw invalidFields;
        }
        int sampleId = samples.size() + 1;
        long timeMs = elapsedMs();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", sampleId);
        values.put("timeMs", timeMs);
        values.putAll(snapshot);
        Ticket ticket = new Ticket(this, sampleId, timeMs, values);
        samples.add(ticket);
        size = samples.size();
        return ticket;
    }

    public synchronized void calculated(Ticket ticket, Map<String, Object> fields) {
        if (accepts(ticket)) ticket.fields.putAll(fields(fields));
    }

    /** Mark event MONITOR completion; incomplete event tickets are excluded at stop. */
    public synchronized void finish(Ticket ticket, Map<String, Object> fields) {
        if (accepts(ticket) && !ticket.finished) {
            ticket.fields.putAll(fields(fields));
            ticket.finished = true;
            completedCount++;
        }
    }

    public synchronized void velocity(Ticket ticket, Map<String, Object> fields) {
        if (accepts(ticket)) ticket.fields.putAll(fields(fields));
    }

    public synchronized void movement(Ticket ticket, Map<String, Object> fields) {
        if (accepts(ticket)) ticket.fields.putAll(fields(fields));
    }

    /** End collection immediately; serialization can then be performed by an independent writer thread. */
    public synchronized void seal() {
        if (sealed) return;
        sealed = true;
        stoppedAt = Instant.now().toString();
        stoppedNanos = System.nanoTime();
        size = completedCount;
    }

    /** Freeze once without holding the region-callback lock during traversal or serialization. */
    public Map<String, Object> stop(String reason, Map<String, Object> endEnvironment,
                                    Map<String, Object> counters) {
        CompletableFuture<Map<String, Object>> result;
        Detached detached = null;
        synchronized (this) {
            if (stoppedFuture == null) {
                Objects.requireNonNull(reason);
                Objects.requireNonNull(endEnvironment);
                Objects.requireNonNull(counters);
                seal();
                result = new CompletableFuture<>();
                stoppedFuture = result;
                detached = new Detached(samples, configurations, observed, omitted, omittedConfigurations,
                        completedCount, stoppedAt, TimeUnit.NANOSECONDS.toMillis(stoppedNanos - startedNanos));
                samples = List.of();
                configurations = List.of();
                configurationIds.clear();
            } else result = stoppedFuture;
        }
        if (detached != null) {
            try {
                result.complete(report(detached, reason, endEnvironment, counters));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            } finally {
                // Callbacks now only test sealed, so this detached mutable storage is no longer shared.
                for (Ticket sample : detached.samples()) sample.fields.clear();
            }
        }
        // A duplicate stop may wait for the writer, but must never hold the callback lock while doing so.
        return result.join();
    }

    private Map<String, Object> report(Detached data, String reason, Map<String, Object> endEnvironment,
                                      Map<String, Object> counters) {
        List<Map<String, Object>> complete = new ArrayList<>(data.completedCount());
        for (Ticket sample : data.samples()) if (sample.finished) complete.add(sample.fields);
        int pending = data.samples().size() - data.completedCount();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", 1);
        report.put("id", id);
        report.put("startedAt", startedAt);
        report.put("stoppedAt", data.stoppedAt());
        report.put("startedBy", startedBy);
        report.put("startedMode", startedMode);
        report.put("stopReason", reason);
        report.put("durationMs", data.durationMs());
        report.put("limits", Map.of("maxSamples", maxSamples, "maxDurationSeconds", maxDurationSeconds));
        report.put("observed", data.observed());
        report.put("omitted", data.omitted() + pending);
        report.put("pendingAtStop", pending);
        report.put("omittedConfigurations", data.omittedConfigurations());
        report.put("samples", complete);
        report.put("configurations", data.configurations());
        report.put("environment", environment);
        report.put("endEnvironment", endEnvironment);
        report.put("counters", counters);
        return ProfilerJson.freezeMap(report);
    }

    public synchronized int size() { return size; }

    private boolean accepts(Ticket ticket) { return !sealed && ticket != null && ticket.owner == this; }
    private long elapsedMs() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos); }

    private static Map<String, Object> fields(Map<String, Object> fields) {
        Objects.requireNonNull(fields);
        if (fields.containsKey("id") || fields.containsKey("timeMs"))
            throw new IllegalArgumentException("id and timeMs are reserved profiler fields");
        return ProfilerJson.freezeMap(fields);
    }
}

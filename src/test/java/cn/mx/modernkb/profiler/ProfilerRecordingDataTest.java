package cn.mx.modernkb.profiler;

import cn.mx.modernkb.api.CombatMode;
import cn.mx.modernkb.config.ClassicSettings;
import cn.mx.modernkb.config.Settings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProfilerRecordingDataTest {
    private static ProfilerRecording recording(int limit) {
        return new ProfilerRecording("test", "Admin", "classic", Map.of("server", "Leaf"), limit, 60);
    }

    private static Settings settings() {
        return new Settings(true, false, true, true, Set.of(), Map.of(), Map.of(), Map.of(),
                CombatMode.CLASSIC, ClassicSettings.defaults());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> samples(Map<String, Object> report) {
        return (List<Map<String, Object>>) report.get("samples");
    }

    @Test public void retainsCompletedEventsWithinCapacityAndAccountsForPending() {
        var recording = recording(2);
        var one = recording.begin(Map.of("type", "melee"));
        var pending = recording.begin(Map.of("type", "arrow"));
        assertNotNull(pending);
        assertNull(recording.begin(Map.of("type", "fishing-rod")));
        recording.calculated(one, Map.of("calculated", List.of(.4, .36, 0)));
        recording.finish(one, Map.of("cancelled", false));
        recording.velocity(one, Map.of("velocity", List.of(.4, .36, 0)));
        recording.movement(one, Map.of("movementTicks", 1));
        Map<String, Object> report = recording.stop("command", Map.of(), Map.of("native", 1));
        assertEquals(3L, report.get("observed"));
        assertEquals(2L, report.get("omitted"));
        assertEquals(1, report.get("pendingAtStop"));
        assertEquals(1, samples(report).size());
        assertEquals(1, samples(report).getFirst().get("id"));
        assertEquals(1, samples(report).getFirst().get("movementTicks"));
        assertEquals(List.of(.4, .36, 0), samples(report).getFirst().get("velocity"));
        assertTrue(Instant.parse((String) report.get("stoppedAt")).compareTo(Instant.parse((String) report.get("startedAt"))) >= 0);
    }

    @Test public void stoppedReportIsDeeplyFrozenAndIdempotent() {
        Map<String, Object> environment = new LinkedHashMap<>();
        var nested = new ArrayList<>(List.of("before"));
        environment.put("plugins", nested);
        var recording = new ProfilerRecording("frozen", "Admin", "modern", environment, 4, 60);
        var vector = new ArrayList<>(List.of(.4, .36, .1));
        var ticket = recording.begin(Map.of("vector", vector));
        vector.set(0, 99.0);
        nested.add("after");
        recording.finish(ticket, Map.of("cancelled", false));
        Map<String, Object> report = recording.stop("command", Map.of(), Map.of());
        String original = ProfilerJson.write(report);
        recording.velocity(ticket, Map.of("late", true));
        recording.movement(ticket, Map.of("late", true));
        recording.calculated(ticket, Map.of("late", true));
        recording.finish(ticket, Map.of("late", true));
        assertNull(recording.begin(Map.of("late", true)));
        assertEquals(-1, recording.configuration(settings()));
        assertSame(report, recording.stop("different", Map.of("changed", true), Map.of()));
        assertEquals(original, ProfilerJson.write(report));
        assertEquals(List.of(.4, .36, .1), samples(report).getFirst().get("vector"));
        assertThrows(UnsupportedOperationException.class, () -> report.put("mutate", true));
        assertThrows(UnsupportedOperationException.class, () -> samples(report).clear());
        assertThrows(UnsupportedOperationException.class, () -> samples(report).getFirst().put("mutate", true));
        assertFalse(ProfilerJson.write(report).contains("after"));
    }

    @Test public void cannotMutateTicketBelongingToAnotherSession() {
        var one = recording(3);
        var two = recording(3);
        var ticket = one.begin(Map.of("original", true));
        two.calculated(ticket, Map.of("foreign", true));
        two.velocity(ticket, Map.of("foreign", true));
        two.movement(ticket, Map.of("foreign", true));
        two.finish(ticket, Map.of("foreign", true));
        one.finish(ticket, Map.of("own", true));
        var report = one.stop("test", Map.of(), Map.of());
        assertEquals(true, samples(report).getFirst().get("own"));
        assertFalse(samples(report).getFirst().containsKey("foreign"));
        assertTrue(samples(two.stop("test", Map.of(), Map.of())).isEmpty());
    }

    @Test public void identitySnapshotDedupeDistinguishesReloadFromSameObject() {
        var recording = recording(2);
        Settings first = settings();
        Settings reload = settings();
        assertEquals(first, reload);
        assertNotSame(first, reload);
        assertEquals(1, recording.configuration(first));
        assertEquals(1, recording.configuration(first));
        assertEquals(2, recording.configuration(reload));
        assertEquals(3, recording.configuration(settings()));
        assertEquals(-1, recording.configuration(settings()));
        var report = recording.stop("test", Map.of(), Map.of());
        assertEquals(3, ((List<?>) report.get("configurations")).size());
        assertEquals(1L, report.get("omittedConfigurations"));
    }

    @Test public void configurationHistoryRemainsBoundedEvenWithManyReloads() {
        var recording = recording(10_000);
        Settings first = settings();
        assertEquals(1, recording.configuration(first));
        for (int i = 2; i <= 64; i++) assertEquals(i, recording.configuration(settings()));
        for (int i = 0; i < 200; i++) assertEquals(-1, recording.configuration(settings()));
        // Existing identities remain usable after the cap; rejected reloads are not retained.
        assertEquals(1, recording.configuration(first));
        var report = recording.stop("test", Map.of(), Map.of());
        assertEquals(64, ((List<?>) report.get("configurations")).size());
        assertEquals(200L, report.get("omittedConfigurations"));
    }

    @Test public void simultaneousRegionCallbacksAndStopProduceConsistentFinalReport() throws Exception {
        var recording = recording(500);
        var executor = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> workers = new ArrayList<>();
            for (int region = 0; region < 4; region++) {
                int index = region;
                workers.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < 500; i++) {
                        var ticket = recording.begin(Map.of("region", index));
                        recording.calculated(ticket, Map.of("calculated", true));
                        recording.finish(ticket, Map.of("final", true));
                        recording.velocity(ticket, Map.of("velocity", true));
                    }
                    return null;
                }));
            }
            Future<Map<String, Object>> stopper = executor.submit(() -> {
                start.await();
                return recording.stop("concurrent", Map.of(), Map.of());
            });
            start.countDown();
            Map<String, Object> report = stopper.get(5, TimeUnit.SECONDS);
            String frozen = ProfilerJson.write(report);
            for (Future<?> worker : workers) worker.get(5, TimeUnit.SECONDS);
            assertEquals(frozen, ProfilerJson.write(report));
            assertTrue(samples(report).size() <= 500);
            assertEquals(((Number) report.get("observed")).longValue(),
                    ((Number) report.get("omitted")).longValue() + samples(report).size());
            assertEquals(samples(report).size(), samples(report).stream().map(s -> s.get("id")).distinct().count());
            for (var sample : samples(report)) assertEquals(true, sample.get("final"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test public void sealStopsCallbacksBeforeTheWriterStarts() {
        var recording = recording(3);
        var accepted = recording.begin(Map.of("accepted", true));
        var pending = recording.begin(Map.of("pending", true));
        recording.finish(accepted, Map.of("final", true));
        recording.seal();
        recording.seal();
        assertEquals(1, recording.size());
        assertNull(recording.begin(Map.of("afterSeal", true)));
        assertEquals(-1, recording.configuration(settings()));
        recording.finish(pending, Map.of("afterSeal", true));
        recording.calculated(accepted, Map.of("afterSeal", true));
        recording.velocity(accepted, Map.of("afterSeal", true));
        recording.movement(accepted, Map.of("afterSeal", true));
        var report = recording.stop("writer", Map.of(), Map.of());
        assertEquals(2L, report.get("observed"));
        assertEquals(1L, report.get("omitted"));
        assertEquals(1, report.get("pendingAtStop"));
        assertEquals(1, samples(report).size());
        assertFalse(ProfilerJson.write(report).contains("afterSeal"));
    }

    @Test public void serializationAndDuplicateStopNeverHoldTheRegionCallbackLock() throws Exception {
        var recording = recording(3);
        var ticket = recording.begin(Map.of("before", true));
        recording.finish(ticket, Map.of("final", true));
        var executor = Executors.newFixedThreadPool(3);
        CountDownLatch serializing = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        Number delayed = new Number() {
            @Override public int intValue() { return 1; }
            @Override public long longValue() { return 1; }
            @Override public float floatValue() { return 1; }
            @Override public double doubleValue() {
                serializing.countDown();
                try {
                    if (!releaseWriter.await(5, TimeUnit.SECONDS)) throw new AssertionError("Writer was not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                return 1;
            }
        };
        try {
            var firstStop = executor.submit(() -> recording.stop("first", Map.of("delayed", delayed), Map.of()));
            assertTrue(serializing.await(5, TimeUnit.SECONDS));
            CountDownLatch duplicateStarted = new CountDownLatch(1);
            var duplicateStop = executor.submit(() -> {
                duplicateStarted.countDown();
                return recording.stop("second", Map.of(), Map.of());
            });
            assertTrue(duplicateStarted.await(5, TimeUnit.SECONDS));
            var callback = executor.submit(() -> {
                recording.velocity(ticket, Map.of("late", true));
                recording.movement(ticket, Map.of("late", true));
                recording.finish(ticket, Map.of("late", true));
                assertNull(recording.begin(Map.of("late", true)));
                assertEquals(-1, recording.configuration(settings()));
                return recording.size();
            });
            assertEquals(Integer.valueOf(1), callback.get(1, TimeUnit.SECONDS));
            assertFalse(firstStop.isDone());
            assertFalse(duplicateStop.isDone());
            releaseWriter.countDown();
            var report = firstStop.get(5, TimeUnit.SECONDS);
            assertSame(report, duplicateStop.get(5, TimeUnit.SECONDS));
            assertEquals("first", report.get("stopReason"));
            assertFalse(ProfilerJson.write(report).contains("late"));
        } finally {
            releaseWriter.countDown();
            executor.shutdownNow();
        }
    }

    @Test public void preventsOverwritingRecordIdentityAndRejectsInvalidLimits() {
        var recording = recording(2);
        assertThrows(IllegalArgumentException.class, () -> recording.begin(Map.of("id", "fake")));
        assertThrows(IllegalArgumentException.class, () -> recording.begin(Map.of("timeMs", -10)));
        assertThrows(IllegalArgumentException.class, () -> recording(0));
        assertThrows(IllegalArgumentException.class, () -> new ProfilerRecording("x", "a", "classic", Map.of(), 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ProfilerRecording("x", "a", "classic", Map.of(), 1, Long.MAX_VALUE));
    }
}

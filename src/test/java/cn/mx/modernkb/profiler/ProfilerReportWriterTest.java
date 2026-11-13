package cn.mx.modernkb.profiler;

import java.nio.file.*;
import java.util.Map;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ProfilerReportWriterTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void standaloneHtmlEmbedsScriptSafeDataAndHasNoExternalAssets() throws Exception {
        Path report = ProfilerReportWriter.write(temporary.getRoot().toPath(), Map.of("id", "example", "schema", 1,
                "startedBy", "</script><img src=x onerror=alert(1)>"));
        String html = Files.readString(report);
        assertFalse(html.contains("{{PROFILER_DATA}}")); assertFalse(html.contains("</script><img"));
        assertTrue(html.contains("\\u003c/script\\u003e")); assertFalse(html.matches("(?s).*<(script|link)[^>]+(src|href)=\"https?://.*"));
    }
    @Test public void collisionNeverOverwritesPreviousReportOrLeavesTemporaryFiles() throws Exception {
        Path folder = temporary.getRoot().toPath(); var data = Map.<String, Object>of("id", "same", "schema", 1);
        Path report = ProfilerReportWriter.write(folder, data); String previous = Files.readString(report);
        try { ProfilerReportWriter.write(folder, data); fail("must not overwrite"); }
        catch (FileAlreadyExistsException expected) { assertEquals(previous, Files.readString(report)); }
        try (var files = Files.list(folder)) { assertEquals(1, files.count()); }
    }
    @Test public void reportNameCannotEscapeProfilerDirectory() throws Exception {
        try { ProfilerReportWriter.write(temporary.getRoot().toPath(), Map.of("id", "../escape")); fail("must reject path"); }
        catch (IllegalArgumentException expected) { assertFalse(Files.exists(temporary.getRoot().toPath().resolveSibling("escape.html"))); }
    }
}

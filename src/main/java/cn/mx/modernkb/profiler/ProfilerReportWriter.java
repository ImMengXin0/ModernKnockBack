package cn.mx.modernkb.profiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

/** A report contains its data and viewer, so it remains usable after copying off the server. */
public final class ProfilerReportWriter {
    private ProfilerReportWriter() {}

    public static Path write(Path directory, Map<String, Object> report) throws IOException {
        String id = String.valueOf(report.get("id"));
        if (!id.matches("[a-zA-Z0-9_-]{1,100}")) throw new IllegalArgumentException("Invalid report id");
        String html = ProfilerHtml.render(ProfilerJson.write(report));
        return save(directory, "kb-" + id + ".html", html);
    }

    static Path save(Path directory, String name, String content) throws IOException {
        directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path destination = directory.resolve(name);
        Path temporary = Files.createTempFile(directory, ".report-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            // A session has a random id. Do not overwrite a previous report, even on a collision.
            Files.move(temporary, destination);
            return destination;
        } finally { Files.deleteIfExists(temporary); }
    }
}

package cn.mx.modernkb.profiler;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Builds the standalone viewer from an already HTML-safe JSON document. */
public final class ProfilerHtml {
    private ProfilerHtml() {}

    public static String render(String json) {
        Objects.requireNonNull(json, "json");
        try (InputStream resource = ProfilerHtml.class.getResourceAsStream("/profiler/viewer.html")) {
            if (resource == null) throw new IllegalStateException("Missing profiler/viewer.html");
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("{{PROFILER_DATA}}", json);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read the profiler viewer", exception);
        }
    }
}

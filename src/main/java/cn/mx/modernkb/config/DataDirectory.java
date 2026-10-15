package cn.mx.modernkb.config;

import java.io.IOException;
import java.nio.file.*;

/** Preserve settings when the plugin name's capitalization changes on case-sensitive filesystems. */
public final class DataDirectory {
    private DataDirectory() {}

    public static boolean migrate(Path previous, Path current) throws IOException {
        if (!Files.isDirectory(previous)) return false;
        if (Files.exists(current)) {
            if (Files.isSameFile(previous, current)) return false;
            // A current installation takes precedence. Never merge or overwrite two installations.
            try (var entries = Files.list(current)) {
                if (entries.findAny().isPresent()) return false;
            }
            Files.delete(current); // Only the verified empty directory created by the loader.
        }
        Files.move(previous, current); // Same parent, no REPLACE_EXISTING; old data stays intact on failure.
        return true;
    }
}

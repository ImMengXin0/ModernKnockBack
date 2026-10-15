package cn.mx.modernkb.config;

import java.nio.file.*;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class DataDirectoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void movesAllLegacySettingsWithoutChangingContent() throws Exception {
        Path previous = temporary.getRoot().toPath().resolve("old-name");
        Path current = previous.resolveSibling("new-name");
        Files.createDirectories(previous.resolve("classic"));
        Files.writeString(previous.resolve("mode.yml"), "mode: classic\n");
        Files.writeString(previous.resolve("classic/profiles.yml"), "custom: 0.5274\n");
        assertTrue(DataDirectory.migrate(previous, current));
        assertFalse(Files.exists(previous));
        assertEquals("mode: classic\n", Files.readString(current.resolve("mode.yml")));
        assertEquals("custom: 0.5274\n", Files.readString(current.resolve("classic/profiles.yml")));
        assertFalse(DataDirectory.migrate(previous, current));
    }
    @Test public void emptyLoaderDirectoryDoesNotHidePreviousMode() throws Exception {
        Path previous = temporary.newFolder("old-name").toPath();
        Path current = temporary.newFolder("new-name").toPath();
        Files.writeString(previous.resolve("mode.yml"), "mode: classic\n");
        assertTrue(DataDirectory.migrate(previous, current));
        assertEquals("mode: classic\n", Files.readString(current.resolve("mode.yml")));
    }
    @Test public void currentSettingsAndSameDirectoryAreNeverOverwritten() throws Exception {
        Path previous = temporary.newFolder("old-name").toPath();
        Path current = temporary.newFolder("new-name").toPath();
        Files.writeString(previous.resolve("mode.yml"), "mode: classic\n");
        Files.writeString(current.resolve("mode.yml"), "mode: modern\n");
        assertFalse(DataDirectory.migrate(previous, current));
        assertFalse(DataDirectory.migrate(current, current));
        assertEquals("mode: classic\n", Files.readString(previous.resolve("mode.yml")));
        assertEquals("mode: modern\n", Files.readString(current.resolve("mode.yml")));
    }
}

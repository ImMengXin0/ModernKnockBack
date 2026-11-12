package cn.mx.modernkb.gui;

import cn.mx.modernkb.config.ModeFiles;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;

public class KnockbackGuiTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void profileMenuShowsOneGlobalDefaultsEntry() throws Exception {
        ModeFiles files = new ModeFiles(temporary.getRoot().toPath());
        files.initialize();

        List<String> profiles = KnockbackGui.sortedProfiles(files.load(files.selected()));

        assertEquals(1, profiles.stream().filter(name -> name.equals("defaults") || name.equals("default")).count());
        assertEquals("defaults", profiles.getFirst());
    }
}

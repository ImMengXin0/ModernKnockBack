package cn.mx.modernkb.combat;

import cn.mx.modernkb.config.KnockbackProfile;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mockStatic;

public class CombatWarmupTest {
    private static int initialized;
    private static class Sample {
        static { initialized++; }
        private static class Nested { static { initialized++; } }
    }

    @Test public void initializesNestedClassesAndIsRepeatable() {
        CombatWarmup.initializeTree(Sample.class);
        assertEquals(2, initialized);
        CombatWarmup.initializeTree(Sample.class);
        assertEquals(2, initialized);
    }

    @Test public void pureWarmupDoesNotCallServerOrChangeProfile() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("native.enabled", true);
        KnockbackProfile profile = KnockbackProfile.read(yaml);
        KnockbackProfile original = KnockbackProfile.read(yaml);
        try (var bukkit = mockStatic(Bukkit.class)) {
            CombatWarmup.prepare(profile);
            CombatWarmup.prepare(profile);
            bukkit.verifyNoInteractions();
        }
        assertEquals(original, profile);
    }

    @Test public void disabledProfileIsSafeWithoutServer() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("enabled", false);
        try (var bukkit = mockStatic(Bukkit.class)) {
            CombatWarmup.prepare(KnockbackProfile.read(yaml));
            bukkit.verifyNoInteractions();
        }
    }
}

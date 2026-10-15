package cn.mx.modernkb;

import cn.mx.modernkb.api.*;
import cn.mx.modernkb.config.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ConfigurationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path dir;
    @Before public void setup() throws Exception {
        dir = temporary.getRoot().toPath();
        for (String name : List.of("config.yml", "profiles.yml"))
            try (var stream = getClass().getResourceAsStream("/" + name)) { Files.copy(Objects.requireNonNull(stream), dir.resolve(name)); }
    }
    @Test public void bundledConfigurationLoadsAndInheritsNativeDefaults() {
        var settings = Settings.load(dir);
        assertTrue(settings.enabled()); assertTrue(settings.preserveSyncY());
        for (var p : settings.profiles().values()) { assertEquals(1, p.groundHorizontal(), 0); assertEquals(1, p.groundVertical(), 0); }
    }
    @Test public void routesBotsSkillsAndMatchups() throws Exception {
        var config = YamlFiles.load(dir.resolve("config.yml"));
        config.set("matchups.arrow.player.bot", "bot"); YamlFiles.save(dir.resolve("config.yml"), config);
        var s = Settings.load(dir);
        HitContext hit = new HitContext(null, null, null, HitType.ARROW, CombatantKind.PLAYER, CombatantKind.BOT, "DAMAGE", true, false, false, 1, false);
        assertEquals("bot", s.select(hit, "player"));
        hit = new HitContext(null, null, null, HitType.MELEE, CombatantKind.BOT, CombatantKind.PLAYER, "DAMAGE", true, false, false, 1, false);
        assertEquals("bot", s.select(hit, "default")); assertEquals("arrow", s.select(hit, "arrow"));
        assertEquals("regularity", s.select(KnockbackMathTest.hit(HitType.REGULARITY, true, false, false, 1, false), "default"));
    }
    @Test public void rejectsMissingProfileReferenceWithoutChangingOldSnapshot() throws Exception {
        var old = Settings.load(dir);
        var config = YamlFiles.load(dir.resolve("config.yml")); config.set("profiles.arrow", "does_not_exist");
        YamlFiles.save(dir.resolve("config.yml"), config);
        assertThrows(IllegalArgumentException.class, () -> Settings.load(dir));
        assertEquals("arrow", old.routes().get("arrow"));
    }
    @Test public void rejectsMistypedSectionUnknownKeyAndBadValue() throws Exception {
        for (var pair : List.of(Map.entry("horizontal", (Object)2), Map.entry("horizontal.groundd", 1), Map.entry("rod.max-distance", 100), Map.entry("modern.sprint-multiplier", "2"))) {
            var yaml = YamlFiles.load(dir.resolve("profiles.yml")); yaml.set("profiles.player." + pair.getKey(), pair.getValue());
            YamlFiles.save(dir.resolve("profiles.yml"), yaml);
            assertThrows(IllegalArgumentException.class, () -> Settings.load(dir));
            yaml.set("profiles.player", Map.of()); YamlFiles.save(dir.resolve("profiles.yml"), yaml);
        }
    }
    @Test public void brokenYamlIsNeverOverwritten() throws Exception {
        String broken = "profiles: [unclosed"; Files.writeString(dir.resolve("profiles.yml"), broken);
        assertThrows(IllegalArgumentException.class, () -> Settings.load(dir));
        assertEquals(broken, Files.readString(dir.resolve("profiles.yml")));
    }
    @Test public void assignmentsAndExclusionsSurviveRepeatedRestartsAndReset() {
        UUID id = UUID.randomUUID(); Path file = dir.resolve("players.yml");
        PlayerStore one = new PlayerStore(file); one.profile(id, "grim_reaper"); one.excluded(id, true);
        PlayerStore two = new PlayerStore(file); assertEquals(new PlayerStore.Preference("grim_reaper", true), two.get(id));
        two.excluded(id, false); assertEquals("grim_reaper", new PlayerStore(file).get(id).profile());
        two.profile(id, "default"); assertEquals(new PlayerStore.Preference("default", false), new PlayerStore(file).get(id));
    }
    @Test public void persistenceFailureDoesNotPublishChange() throws Exception {
        Path file = dir.resolve("not-a-file"); Files.createDirectory(file); Files.writeString(file.resolve("keep"), "x");
        // Create the store first, then obstruct the destination.
        Path target = dir.resolve("players.yml"); PlayerStore store = new PlayerStore(target);
        Files.createDirectory(target); Files.writeString(target.resolve("keep"), "x");
        UUID id = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> store.profile(id, "bot"));
        assertEquals("default", store.get(id).profile());
    }
    @Test public void rejectsCorruptPlayerDataInsteadOfResettingIt() throws Exception {
        Path file = dir.resolve("players.yml"); String broken = "players:\n  invalid-uuid:\n    profile: player\n";
        Files.writeString(file, broken);
        assertThrows(IllegalArgumentException.class, () -> new PlayerStore(file));
        assertEquals(broken, Files.readString(file));
    }
    @Test public void oldConfigurationWithoutNativeSectionKeepsImpulseBehavior() throws Exception {
        var yaml = YamlFiles.load(dir.resolve("profiles.yml")); yaml.set("defaults.native", null);
        YamlFiles.save(dir.resolve("profiles.yml"), yaml);
        var settings = Settings.load(dir);
        assertFalse(settings.profiles().get("player").nativeSettings().enabled());
        assertEquals(KnockbackProfile.Native.defaults(), settings.profiles().get("player").nativeSettings());
    }
    @Test public void nativeInheritanceAndDiskReloadKeepPerProfileOverrides() throws Exception {
        var yaml = YamlFiles.load(dir.resolve("profiles.yml"));
        yaml.set("defaults.native.horizontal-retention-ground", 0.6);
        yaml.set("profiles.player.native.enabled", true);
        yaml.set("profiles.player.native.horizontal-retention-air", 0.4);
        YamlFiles.save(dir.resolve("profiles.yml"), yaml);
        var first = Settings.load(dir);
        assertTrue(first.profiles().get("player").nativeSettings().enabled());
        assertEquals(0.6, first.profiles().get("player").nativeSettings().groundHorizontalRetention(), 0);
        assertEquals(0.4, first.profiles().get("player").nativeSettings().airHorizontalRetention(), 0);
        assertFalse(first.profiles().get("bot").nativeSettings().enabled());
        assertEquals(first, Settings.load(dir));
    }
    @Test public void malformedNativeSectionRejectsReloadAndPreservesSnapshot() throws Exception {
        var previous = Settings.load(dir);
        for (var value : List.of(Map.entry("native", (Object)2), Map.entry("native.air-vertical-mode", "replace"),
                Map.entry("native.horizontal-retention-air", 2), Map.entry("native.typo", true))) {
            var yaml = YamlFiles.load(dir.resolve("profiles.yml"));
            yaml.set("profiles.player", Map.of());
            yaml.set("profiles.player." + value.getKey(), value.getValue());
            YamlFiles.save(dir.resolve("profiles.yml"), yaml);
            assertThrows(IllegalArgumentException.class, () -> Settings.load(dir));
            assertFalse(previous.profiles().get("player").nativeSettings().enabled());
        }
    }
    @Test public void shippedBalancedPresetLoadsWithNativeForCombatOnly() {
        var preset = Settings.load(Path.of("presets/balanced-modern"));
        for (String name : List.of("player", "bot", "arrow", "crossbow", "sweep", "fishing_rod"))
            assertTrue(name, preset.profiles().get(name).nativeSettings().enabled());
        for (String name : List.of("default", "regularity", "grim_reaper", "wind_charge", "explosion", "crystal", "tnt"))
            assertFalse(name, preset.profiles().get(name).nativeSettings().enabled());
        for (HitType type : HitType.values()) assertTrue(preset.profiles().containsKey(preset.select(
                KnockbackMathTest.hit(type, true, false, false, 1, false), "default")));
    }
}

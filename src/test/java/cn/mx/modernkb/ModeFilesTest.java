package cn.mx.modernkb;

import cn.mx.modernkb.api.*;
import cn.mx.modernkb.config.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ModeFilesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void firstStartCreatesBothModesAndRestartsInSavedMode() throws Exception {
        Path root = temporary.getRoot().toPath();
        ModeFiles files = new ModeFiles(root); files.initialize();
        assertEquals(CombatMode.MODERN, files.selected());
        for (CombatMode mode : CombatMode.values()) {
            var config = files.load(mode);
            assertEquals(mode, config.mode());
            for (HitType type : HitType.values()) assertTrue(config.profiles().containsKey(config.select(
                    KnockbackMathTest.hit(type, true, false, false, 1, false), "default")));
        }
        files.save(CombatMode.CLASSIC);
        ModeFiles restarted = new ModeFiles(root); restarted.initialize();
        assertEquals(CombatMode.CLASSIC, restarted.selected());
        assertEquals(10, restarted.load(restarted.selected()).classic().hitIntervalTicks());
        assertFalse(restarted.load(restarted.selected()).classic().stopSprint());
    }
    @Test public void migrationCopiesExistingModernConfigAndNeverOverwritesEitherMode() throws Exception {
        Path root = temporary.getRoot().toPath();
        for (String name : List.of("config.yml", "profiles.yml"))
            try (var stream = getClass().getResourceAsStream("/" + name)) { Files.copy(stream, root.resolve(name)); }
        var yaml = YamlFiles.load(root.resolve("profiles.yml")); yaml.set("profiles.player.horizontal.air", 0.73);
        YamlFiles.save(root.resolve("profiles.yml"), yaml);
        ModeFiles files = new ModeFiles(root); files.initialize();
        assertEquals(0.73, files.load(CombatMode.MODERN).profiles().get("player").airHorizontal(), 0);
        String classic = Files.readString(root.resolve("classic/profiles.yml"));
        files.initialize();
        assertEquals(classic, Files.readString(root.resolve("classic/profiles.yml")));
        assertEquals(Files.readString(root.resolve("profiles.yml")), Files.readString(root.resolve("modern/profiles.yml")));
    }
    @Test public void invalidTargetDoesNotChangeSavedModeOrDestroyFile() throws Exception {
        Path root = temporary.getRoot().toPath(); ModeFiles files = new ModeFiles(root); files.initialize();
        Files.writeString(root.resolve("classic/combat.yml"), "hit-interval-ticks: 0\n");
        assertThrows(IllegalArgumentException.class, () -> files.load(CombatMode.CLASSIC));
        assertEquals(CombatMode.MODERN, files.selected());
        assertEquals("hit-interval-ticks: 0\n", Files.readString(root.resolve("classic/combat.yml")));
        Files.writeString(root.resolve("mode.yml"), "mode: clasic\n");
        assertThrows(IllegalArgumentException.class, files::selected);
    }
    @Test public void classicRejectsUnknownOrInvalidVectorAndTimingFields() throws Exception {
        Path root = temporary.getRoot().toPath(); ModeFiles files = new ModeFiles(root); files.initialize();
        var yaml = YamlFiles.load(root.resolve("classic/profiles.yml"));
        yaml.set("profiles.player.classic.horizontal-retention", 1.1);
        YamlFiles.save(root.resolve("classic/profiles.yml"), yaml);
        assertThrows(IllegalArgumentException.class, () -> files.load(CombatMode.CLASSIC));
        assertEquals(CombatMode.MODERN, files.load(CombatMode.MODERN).mode());
    }
    @Test public void saveFailureRetainsPriorMode() throws Exception {
        Path root = temporary.getRoot().toPath(); ModeFiles files = new ModeFiles(root); files.initialize();
        Files.delete(root.resolve("mode.yml")); Files.createDirectory(root.resolve("mode.yml"));
        Files.writeString(root.resolve("mode.yml/keep"), "keep");
        assertThrows(java.io.IOException.class, () -> files.save(CombatMode.CLASSIC));
        assertEquals("keep", Files.readString(root.resolve("mode.yml/keep")));
    }
    @Test public void compactHorizontalOverridesBothInheritedAxesAndStillSupportsOldFields() throws Exception {
        Path root = temporary.getRoot().toPath(); ModeFiles files = new ModeFiles(root); files.initialize();
        var yaml = YamlFiles.load(root.resolve("classic/profiles.yml"));
        yaml.set("profiles.player.classic.horizontal", 0.6);
        yaml.set("profiles.bot.classic.horizontal-air", 0.3);
        YamlFiles.save(root.resolve("classic/profiles.yml"), yaml);
        var classic = files.load(CombatMode.CLASSIC);
        assertEquals(0.6, classic.profiles().get("player").classic().horizontalGround(), 0);
        assertEquals(0.6, classic.profiles().get("player").classic().horizontalAir(), 0);
        assertEquals(0.4, classic.profiles().get("bot").classic().horizontalGround(), 0);
        assertEquals(0.3, classic.profiles().get("bot").classic().horizontalAir(), 0);
        yaml.set("profiles.player.classic.horizontal", 4.1);
        YamlFiles.save(root.resolve("classic/profiles.yml"), yaml);
        assertThrows(IllegalArgumentException.class, () -> files.load(CombatMode.CLASSIC));
    }
    @Test public void classicDefaultsHaveFiveMainParametersAndNoHiddenHeightOrMomentumVariation() throws Exception {
        Path root = temporary.getRoot().toPath(); ModeFiles files = new ModeFiles(root); files.initialize();
        var yaml = YamlFiles.load(root.resolve("classic/profiles.yml"));
        assertEquals(Set.of("horizontal", "vertical-ground", "vertical-air", "sprint-horizontal", "sprint-vertical"),
                yaml.getConfigurationSection("defaults.classic").getKeys(false));
        for (var profile : files.load(CombatMode.CLASSIC).profiles().values()) {
            assertFalse(profile.classic().yLimit());
            assertEquals(0, profile.classic().horizontalRetention(), 0);
            assertEquals(0, profile.classic().verticalRetention(), 0);
        }
    }
    @Test public void stopSprintDefaultsOffAndLegacyFieldStillLoadsWithoutOverwriting() throws Exception {
        Path root = temporary.getRoot().toPath(); ModeFiles files = new ModeFiles(root); files.initialize();
        Path combat = root.resolve("classic/combat.yml");
        assertFalse(files.load(CombatMode.CLASSIC).classic().stopSprint());
        assertFalse(new ClassicSettings(10, 4, true, true).resetSprintOnHit());
        Files.writeString(combat, "reset-sprint-on-hit: true\n");
        files.initialize();
        assertTrue(files.load(CombatMode.CLASSIC).classic().stopSprint());
        assertTrue(Files.readString(combat).contains("reset-sprint-on-hit: true"));
        assertFalse(YamlFiles.load(combat).contains("stop_sprint"));
        Files.writeString(combat, "reset-sprint-on-hit: false\n");
        assertFalse(files.load(CombatMode.CLASSIC).classic().stopSprint());
        Files.writeString(combat, "hit-interval-ticks: 10\n");
        assertFalse(files.load(CombatMode.CLASSIC).classic().stopSprint());
    }
    @Test public void rodPresetOverridesOldHalfStrengthWithoutChangingCustomizedMelee() throws Exception {
        Path root = temporary.getRoot().toPath(); ModeFiles files = new ModeFiles(root); files.initialize();
        Path file = root.resolve("classic/profiles.yml");
        var yaml = YamlFiles.load(file);
        yaml.set("defaults.classic.horizontal-ground", 0.5274);
        yaml.set("defaults.classic.horizontal-air", 0.5274);
        yaml.set("defaults.classic.vertical-ground", 0.361375);
        yaml.set("defaults.rod.impact-strength", 0.2);
        yaml.set("profiles.fishing_rod.classic", null); yaml.set("profiles.fishing_rod.rod", null);
        var patch = YamlFiles.load(Path.of("presets/classic-rod-responsive.yml"));
        for (String key : patch.getKeys(true)) if (!patch.isConfigurationSection(key)) yaml.set(key, patch.get(key));
        YamlFiles.save(file, yaml);
        var config = files.load(CombatMode.CLASSIC);
        assertEquals(0.5274, config.profiles().get("player").classic().horizontalGround(), 0);
        assertEquals(0.361375, config.profiles().get("player").classic().verticalGround(), 0);
        assertEquals("fishing_rod", config.rodProfile("default"));
        assertEquals("fishing_rod", config.select(KnockbackMathTest.hit(HitType.FISHING_ROD, true, false, false, 1, false), "default"));
        var rod = config.profiles().get("fishing_rod");
        assertEquals(0.5, rod.classic().horizontalGround(), 0); assertEquals(0.5, rod.classic().horizontalAir(), 0);
        assertEquals(0.34, rod.classic().verticalGround(), 0); assertEquals(0.34, rod.classic().verticalAir(), 0);
        assertEquals(0.4, rod.rod().impactStrength(), 0);
        assertEquals(2, rod.rod().castTicks()); assertEquals(0, rod.rod().recastTicks());
    }
    @Test public void rodMeleeIntervalValidatesBoundsAndRetainsExistingApiConstructors() throws Exception {
        Path root = temporary.getRoot().toPath(); ModeFiles files = new ModeFiles(root); files.initialize();
        Path combat = root.resolve("classic/combat.yml");
        assertEquals(10, files.load(CombatMode.CLASSIC).classic().rodMeleeIntervalTicks());
        assertEquals(4, new ClassicSettings(10, 4, true, true).rodMeleeIntervalTicks());
        assertEquals(4, new ClassicSettings(10, 4, true, true, false, true, 2).rodMeleeIntervalTicks());
        for (String value : List.of("0", "-1", "41", "1.5", "'2'", "false")) {
            String invalid = "rod-melee-interval-ticks: " + value + "\n";
            Files.writeString(combat, invalid);
            assertThrows(IllegalArgumentException.class, () -> files.load(CombatMode.CLASSIC));
            assertEquals(invalid, Files.readString(combat));
        }
        for (int value : new int[]{1, 2, 40}) {
            Files.writeString(combat, "rod-melee-interval-ticks: " + value + "\n");
            assertEquals(value, files.load(CombatMode.CLASSIC).classic().rodMeleeIntervalTicks());
        }
    }
    @Test public void stopSprintRejectsConflictingAliasesAndNonBooleanValues() throws Exception {
        Path root = temporary.getRoot().toPath(); ModeFiles files = new ModeFiles(root); files.initialize();
        Path combat = root.resolve("classic/combat.yml");
        Files.writeString(combat, "stop_sprint: false\nreset-sprint-on-hit: true\n");
        assertThrows(IllegalArgumentException.class, () -> files.load(CombatMode.CLASSIC));
        for (String key : List.of("stop_sprint", "reset-sprint-on-hit")) {
            Files.writeString(combat, key + ": 'false'\n");
            assertThrows(IllegalArgumentException.class, () -> files.load(CombatMode.CLASSIC));
        }
        Files.writeString(combat, "stop_sprint: false\nreset-sprint-on-hit: false\n");
        assertFalse(files.load(CombatMode.CLASSIC).classic().stopSprint());
        Files.writeString(combat, "stop_sprint: true\n");
        assertTrue(files.load(CombatMode.CLASSIC).classic().stopSprint());
        assertEquals(CombatMode.MODERN, files.selected());
    }
}

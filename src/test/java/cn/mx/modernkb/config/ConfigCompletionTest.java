package cn.mx.modernkb.config;

import cn.mx.modernkb.api.CombatMode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ConfigCompletionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path root;
    private ModeFiles files;
    @Before public void setup() throws Exception {
        root = temporary.getRoot().toPath(); files = new ModeFiles(root); files.initialize();
    }
    private List<Path> backups() throws Exception {
        Path path = root.resolve("backups");
        if (!Files.exists(path)) return List.of();
        try (var stream = Files.walk(path)) { return stream.filter(Files::isRegularFile).toList(); }
    }
    @Test public void completesRodMeleeIntervalOnUpgradeAndKeepsCustomizedValueAfterRestart() throws Exception {
        files.load(CombatMode.CLASSIC);
        Path combat = root.resolve("classic/combat.yml");
        var old = YamlFiles.load(combat); old.set("rod-melee-interval-ticks", null); old.set("hit-interval-ticks", 8);
        YamlFiles.save(combat, old);
        String original = Files.readString(combat); int backupCount = backups().size();
        var completed = files.load(CombatMode.CLASSIC);
        assertEquals(10, completed.classic().rodMeleeIntervalTicks());
        assertEquals(8, completed.classic().hitIntervalTicks());
        assertEquals(10, YamlFiles.load(combat).getInt("rod-melee-interval-ticks"));
        assertEquals(backupCount + 1, backups().size());
        boolean originalSaved = false;
        for (Path backup : backups()) if (Files.readString(backup).equals(original)) originalSaved = true;
        assertTrue(originalSaved);
        var customized = YamlFiles.load(combat); customized.set("rod-melee-interval-ticks", 1);
        YamlFiles.save(combat, customized); String customizedText = Files.readString(combat);
        files.save(CombatMode.CLASSIC);
        ModeFiles restarted = new ModeFiles(root); restarted.initialize();
        assertEquals(1, restarted.load(restarted.selected()).classic().rodMeleeIntervalTicks());
        assertEquals(10, restarted.load(restarted.selected()).classic().effectiveRodMeleeTicks());
        assertEquals(customizedText, Files.readString(combat)); assertEquals(backupCount + 1, backups().size());
    }
    @Test public void completesClassicAndRodPreservingLegacyAxesInheritedValuesCommentsAndFalseZero() throws Exception {
        Path config = root.resolve("classic/config.yml"), profiles = root.resolve("classic/profiles.yml"), combat = root.resolve("classic/combat.yml");
        String originalConfig = "# My routing comment\nconfig-version: 1\nenabled: false # keep disabled\nprofiles:\n  fishing-rod: my_rod\n";
        String originalProfiles = """
                # My KB comment
                defaults:
                  enabled: false
                  classic:
                    horizontal-ground: 0.5274
                    horizontal-air: 0.6
                    vertical-ground: 0.361375
                    vertical-air: 0.361375
                  rod:
                    enabled: false
                    cast-interval-ticks: 0
                    impact-strength: 0.0
                profiles:
                  default: {}
                  player: {}
                  fishing_rod: {}
                  my_rod:
                    classic:
                      horizontal: 0.77
                """;
        String originalCombat = "# My combat comment\nhit-interval-ticks: 8\nreset-sprint-on-hit: true\n";
        Files.writeString(config, originalConfig); Files.writeString(profiles, originalProfiles); Files.writeString(combat, originalCombat);
        var completed = files.load(CombatMode.CLASSIC);
        assertFalse(completed.enabled()); assertEquals("my_rod", completed.routes().get("fishing-rod"));
        var player = completed.profiles().get("player");
        assertEquals(0.5274, player.classic().horizontalGround(), 0); assertEquals(0.6, player.classic().horizontalAir(), 0);
        var rod = completed.profiles().get("fishing_rod");
        assertEquals(0.5274, rod.classic().horizontalGround(), 0); assertEquals(0.6, rod.classic().horizontalAir(), 0);
        assertEquals(0.361375, rod.classic().verticalGround(), 0); assertFalse(rod.rod().enabled());
        assertEquals(0, rod.rod().castTicks()); assertEquals(0, rod.rod().impactStrength(), 0);
        assertEquals(0.77, completed.profiles().get("my_rod").classic().horizontalAir(), 0);
        assertEquals(8, completed.classic().hitIntervalTicks()); assertTrue(completed.classic().stopSprint());
        assertFalse(YamlFiles.load(combat).contains("stop_sprint"));
        assertFalse(YamlFiles.load(profiles).contains("defaults.classic.horizontal"));
        assertTrue(YamlFiles.load(profiles).contains("profiles.fishing_rod.rod.vertical-speed-multiplier"));
        assertTrue(Files.readString(config).contains("My routing comment")); assertTrue(Files.readString(config).contains("keep disabled"));
        assertTrue(Files.readString(profiles).contains("My KB comment")); assertTrue(Files.readString(combat).contains("My combat comment"));
        List<String> originals = new ArrayList<>(); for (Path backup : backups()) originals.add(Files.readString(backup));
        assertEquals(3, originals.size()); assertTrue(originals.containsAll(List.of(originalConfig, originalProfiles, originalCombat)));
        String once = Files.readString(profiles); files.load(CombatMode.CLASSIC);
        assertEquals(once, Files.readString(profiles)); assertEquals(3, backups().size());
    }
    @Test public void missingRodStrengthAndProfilesArePersistedAndLoadedOnRestart() throws Exception {
        Path profiles = root.resolve("classic/profiles.yml");
        Files.writeString(profiles, "defaults:\n  classic:\n    horizontal: 0.5274\nprofiles:\n  default: {}\n  fishing_rod:\n    rod:\n      cast-interval-ticks: 0\n      horizontal-speed-multiplier: 1.2\n");
        var completed = files.load(CombatMode.CLASSIC);
        var yaml = YamlFiles.load(profiles);
        assertEquals(0.4, yaml.getDouble("defaults.rod.impact-strength"), 0);
        assertEquals(0.4, yaml.getDouble("profiles.fishing_rod.rod.impact-strength"), 0);
        assertEquals(0.5274, completed.profiles().get("fishing_rod").classic().horizontalGround(), 0);
        assertEquals(0, completed.profiles().get("fishing_rod").rod().castTicks());
        assertEquals(1.2, completed.profiles().get("fishing_rod").rod().horizontalSpeed(), 0);
        assertTrue(completed.profiles().containsKey("player")); assertTrue(completed.profiles().containsKey("bot"));
        files.save(CombatMode.CLASSIC);
        ModeFiles restarted = new ModeFiles(root); restarted.initialize();
        assertEquals(completed, restarted.load(restarted.selected())); assertEquals(1, backups().size());
    }
    @Test public void customizedClassicRodKbAndTimingSurviveRestartWithoutReset() throws Exception {
        Path profiles = root.resolve("classic/profiles.yml"), combat = root.resolve("classic/combat.yml");
        var profileYaml = YamlFiles.load(profiles);
        profileYaml.set("profiles.fishing_rod.classic.horizontal", 0.61);
        profileYaml.set("profiles.fishing_rod.classic.vertical-ground", 0.31);
        profileYaml.set("profiles.fishing_rod.classic.vertical-air", 0.28);
        profileYaml.set("profiles.fishing_rod.rod.cast-interval-ticks", 7);
        profileYaml.set("profiles.fishing_rod.rod.recast-delay-ticks", 3);
        profileYaml.set("profiles.fishing_rod.rod.horizontal-speed-multiplier", 1.25);
        profileYaml.set("profiles.fishing_rod.rod.vertical-speed-multiplier", 0.9);
        profileYaml.set("profiles.fishing_rod.rod.max-distance", 18.0);
        profileYaml.set("profiles.fishing_rod.rod.max-lifetime-ticks", 240);
        profileYaml.set("profiles.fishing_rod.rod.impact-strength", 0.55);
        YamlFiles.save(profiles, profileYaml);
        var combatYaml = YamlFiles.load(combat);
        combatYaml.set("rod-hit-interval-ticks", 6);
        combatYaml.set("rod-melee-interval-ticks", 3);
        YamlFiles.save(combat, combatYaml);

        var beforeRestart = files.load(CombatMode.CLASSIC);
        var beforeRod = beforeRestart.profiles().get("fishing_rod");
        files.save(CombatMode.CLASSIC);
        ModeFiles restarted = new ModeFiles(root);
        restarted.initialize();
        var afterRestart = restarted.load(restarted.selected());
        var afterRod = afterRestart.profiles().get("fishing_rod");
        assertEquals(CombatMode.CLASSIC, restarted.selected());
        assertEquals(0.61, afterRod.classic().horizontalGround(), 0);
        assertEquals(0.61, afterRod.classic().horizontalAir(), 0);
        assertEquals(0.31, afterRod.classic().verticalGround(), 0);
        assertEquals(0.28, afterRod.classic().verticalAir(), 0);
        assertEquals(7, afterRod.rod().castTicks());
        assertEquals(3, afterRod.rod().recastTicks());
        assertEquals(1.25, afterRod.rod().horizontalSpeed(), 0);
        assertEquals(0.9, afterRod.rod().verticalSpeed(), 0);
        assertEquals(18.0, afterRod.rod().maxDistance(), 0);
        assertEquals(240, afterRod.rod().lifetimeTicks());
        assertEquals(0.55, afterRod.rod().impactStrength(), 0);
        assertEquals(6, afterRestart.classic().rodHitIntervalTicks());
        assertEquals(3, afterRestart.classic().rodMeleeIntervalTicks());
        assertEquals(10, afterRestart.classic().effectiveRodHitTicks());
        assertEquals(10, afterRestart.classic().effectiveRodMeleeTicks());
        assertEquals(beforeRod, afterRod);
    }
    @Test public void modernExistingProfilesKeepInheritedOverridesAndCustomProfileIsNotExpanded() throws Exception {
        Path profiles = root.resolve("modern/profiles.yml");
        Files.writeString(profiles, "defaults:\n  native:\n    enabled: false\n  horizontal:\n    air: 0.82\nprofiles:\n  default: {}\n  player: {}\n  custom:\n    rod:\n      enabled: false\n      impact-strength: 0.0\n");
        var completed = files.load(CombatMode.MODERN);
        assertEquals(0.82, completed.profiles().get("player").airHorizontal(), 0);
        assertFalse(completed.profiles().get("player").nativeSettings().enabled());
        assertFalse(completed.profiles().get("custom").rod().enabled());
        assertEquals(0, completed.profiles().get("custom").rod().impactStrength(), 0);
        var yaml = YamlFiles.load(profiles);
        assertEquals(Set.of("rod"), yaml.getConfigurationSection("profiles.custom").getKeys(false));
        assertFalse(yaml.contains("profiles.player.horizontal.air")); assertFalse(yaml.contains("profiles.player.native.enabled"));
        assertTrue(yaml.contains("defaults.rod.max-distance"));
    }
    @Test public void invalidValueScalarSectionOrSyntaxLeavesAllFilesAndBackupsUntouched() throws Exception {
        Path config = root.resolve("classic/config.yml"), profiles = root.resolve("classic/profiles.yml");
        String partial = "config-version: 1\n";
        for (String broken : List.of("defaults:\n  rod:\n    cast-interval-ticks: -1\nprofiles:\n  default: {}\n", "defaults: broken\nprofiles:\n  default: {}\n", "defaults: [\n")) {
            Files.writeString(config, partial); Files.writeString(profiles, broken);
            assertThrows(IllegalArgumentException.class, () -> files.load(CombatMode.CLASSIC));
            assertEquals(partial, Files.readString(config)); assertEquals(broken, Files.readString(profiles));
            assertTrue(backups().isEmpty());
        }
        Files.writeString(config, "config-version: 9\n");
        Files.writeString(profiles, "defaults: {}\nprofiles:\n  default: {}\n");
        assertThrows(IllegalArgumentException.class, () -> files.load(CombatMode.CLASSIC));
        assertEquals("config-version: 9\n", Files.readString(config)); assertTrue(backups().isEmpty());
    }
    @Test public void failureToCreateBackupDoesNotOverwritePartialConfiguration() throws Exception {
        Path profiles = root.resolve("classic/profiles.yml");
        String original = "defaults: {}\nprofiles:\n  default: {}\n";
        Files.writeString(profiles, original); Files.writeString(root.resolve("backups"), "keep");
        assertThrows(IllegalStateException.class, () -> files.load(CombatMode.CLASSIC));
        assertEquals(original, Files.readString(profiles)); assertEquals(CombatMode.MODERN, files.selected());
        assertEquals("keep", Files.readString(root.resolve("backups")));
    }
    @Test public void failedSecondWriteRollsBackFirstFileToExactOriginalText() throws Exception {
        Path first = root.resolve("first.yml"), second = root.resolve("second.yml");
        String original = "# custom original\nvalue: 0\n";
        Files.writeString(first, original); Files.writeString(second, original);
        var template = ConfigCompletion.parse("value: 1\nadded: true\n", "test template");
        var firstDraft = ConfigCompletion.complete(first, template, false, false);
        var secondDraft = ConfigCompletion.complete(second, template, false, false);
        try (var io = mockStatic(YamlFiles.class, call -> {
            if (call.getMethod().getName().equals("save") && call.getArgument(0).equals(second)) throw new IOException("injected write failure");
            return call.callRealMethod();
        })) {
            assertThrows(IOException.class, () -> ConfigCompletion.saveValidated(root, List.of(firstDraft, secondDraft), message -> {}));
        }
        assertEquals(original, Files.readString(first)); assertEquals(original, Files.readString(second));
        assertEquals(2, backups().size());
    }
    @Test public void completionDetectsAnEditorChangeBeforeReplacingOrBackingUpFile() throws Exception {
        Path file = root.resolve("edit.yml"); Files.writeString(file, "enabled: false\n");
        var draft = ConfigCompletion.complete(file, ConfigCompletion.parse("enabled: true\nnew-key: 1\n", "test"), false, false);
        Files.writeString(file, "enabled: true # edited meanwhile\n");
        assertThrows(IOException.class, () -> ConfigCompletion.saveValidated(root, List.of(draft), message -> {}));
        assertEquals("enabled: true # edited meanwhile\n", Files.readString(file)); assertTrue(backups().isEmpty());
    }
    @Test public void emptyModeFileCompletesOnceButInvalidSelectionIsPreserved() throws Exception {
        Path mode = root.resolve("mode.yml"); Files.writeString(mode, "# select later\n");
        assertEquals(CombatMode.MODERN, files.selected()); assertTrue(Files.readString(mode), Files.readString(mode).contains("select later"));
        assertEquals(1, backups().size()); files.selected(); assertEquals(1, backups().size());
        Files.writeString(mode, "mode: clasic\n");
        assertThrows(IllegalArgumentException.class, files::selected);
        assertEquals("mode: clasic\n", Files.readString(mode)); assertEquals(1, backups().size());
    }
    @Test public void deletedModeFileIsCreatedOnReloadAndExistingOthersStayByteIdentical() throws Exception {
        Path config = root.resolve("modern/config.yml"), profiles = root.resolve("modern/profiles.yml");
        String original = Files.readString(profiles); Files.delete(config);
        assertEquals(CombatMode.MODERN, files.load(CombatMode.MODERN).mode());
        assertTrue(Files.exists(config)); assertEquals(original, Files.readString(profiles)); assertTrue(backups().isEmpty());
    }
}

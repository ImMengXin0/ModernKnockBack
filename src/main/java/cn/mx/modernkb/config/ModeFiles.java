package cn.mx.modernkb.config;

import cn.mx.modernkb.api.CombatMode;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.bukkit.configuration.file.YamlConfiguration;

/** Non-destructive first-run migration and durable mode selection. */
public final class ModeFiles {
    private final Path root;
    private final Consumer<String> report;
    public ModeFiles(Path root) { this(root, message -> {}); }
    public ModeFiles(Path root, Consumer<String> report) { this.root = root.toAbsolutePath(); this.report = Objects.requireNonNull(report); }
    public void initialize() throws IOException {
        for (CombatMode mode : CombatMode.values()) {
            Path directory = root.resolve(mode.key());
            Files.createDirectories(directory);
            for (String name : List.of("config.yml", "profiles.yml")) {
                Path destination = directory.resolve(name);
                if (Files.exists(destination)) continue;
                Path legacy = root.resolve(name);
                if (mode == CombatMode.MODERN && Files.exists(legacy)) Files.copy(legacy, destination);
                else copyResource(mode.key() + "/" + name, destination);
            }
        }
        Path combat = root.resolve("classic/combat.yml");
        if (!Files.exists(combat)) copyResource("classic/combat.yml", combat);
        if (!Files.exists(root.resolve("mode.yml"))) save(CombatMode.MODERN);
    }
    private void copyResource(String resource, Path destination) throws IOException {
        try (var in = ModeFiles.class.getResourceAsStream("/" + resource)) {
            if (in == null) throw new IOException("Missing bundled resource: " + resource);
            Files.copy(in, destination);
        }
    }
    public CombatMode selected() {
        try {
            var template = ConfigCompletion.parse("mode: modern\n", "mode defaults");
            var draft = ConfigCompletion.complete(root.resolve("mode.yml"), template, false, false);
            var yaml = draft.yaml();
            if (!yaml.getKeys(false).equals(Set.of("mode")) || !yaml.isString("mode")) throw new IllegalArgumentException("Invalid mode.yml; expected mode: classic or modern");
            CombatMode mode = CombatMode.parse(yaml.getString("mode"));
            ConfigCompletion.saveValidated(root, List.of(draft), report);
            return mode;
        } catch (IOException ex) { throw new IllegalStateException("Cannot complete mode.yml", ex); }
    }
    public Settings load(CombatMode mode) {
        try {
            boolean classic = mode == CombatMode.CLASSIC;
            Path directory = root.resolve(mode.key());
            var config = ConfigCompletion.complete(directory.resolve("config.yml"), ConfigCompletion.resource(mode.key() + "/config.yml"), false, classic);
            var profiles = ConfigCompletion.complete(directory.resolve("profiles.yml"), ConfigCompletion.resource(mode.key() + "/profiles.yml"), true, classic);
            List<ConfigCompletion.Draft> drafts = new ArrayList<>(List.of(config, profiles));
            ClassicSettings combat = ClassicSettings.defaults();
            if (classic) {
                var draft = ConfigCompletion.complete(directory.resolve("combat.yml"), ConfigCompletion.resource("classic/combat.yml"), false, true);
                drafts.add(draft); combat = ClassicSettings.read(draft.yaml());
            }
            Settings candidate = Settings.read(config.yaml(), profiles.yaml()).withMode(mode, combat);
            ConfigCompletion.saveValidated(root, drafts, report);
            return candidate;
        } catch (IOException ex) { throw new IllegalStateException("Cannot complete " + mode.key() + " configuration; previous runtime state retained", ex); }
    }
    public void save(CombatMode mode) throws IOException {
        var yaml = new YamlConfiguration();
        yaml.set("mode", mode.key());
        YamlFiles.save(root.resolve("mode.yml"), yaml);
    }
}

package cn.mx.modernkb.config;

import cn.mx.modernkb.api.CombatMode;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.bukkit.configuration.file.YamlConfiguration;

/** Non-destructive first-run migration and durable mode selection. */
public final class ModeFiles {
    private static final Set<String> GUI_PROFILE_FIELDS = Set.of("enabled", "classic.horizontal-ground", "classic.horizontal-air",
            "classic.vertical-ground", "classic.vertical-air", "classic.sprint-horizontal", "classic.sprint-vertical",
            "classic.enchantment-horizontal", "classic.horizontal-retention", "classic.vertical-retention", "classic.vertical-max",
            "classic.respect-resistance", "classic.y-limit-enabled", "classic.max-height", "classic.vertical-after-limit",
            "horizontal.ground", "horizontal.air", "vertical.ground", "vertical.air", "modern.sprint-multiplier",
            "modern.critical-multiplier", "modern.weak-hit-multiplier", "limits.horizontal", "limits.upward", "limits.downward",
            "native.enabled", "native.horizontal-retention-ground", "native.horizontal-retention-air",
            "native.vertical-retention-ground", "native.vertical-max-ground", "native.air-vertical-mode", "native.vertical-max-air",
            "rod.enabled", "rod.cast-interval-ticks", "rod.recast-delay-ticks", "rod.horizontal-speed-multiplier",
            "rod.vertical-speed-multiplier", "rod.gravity-multiplier", "rod.max-distance", "rod.max-lifetime-ticks",
            "rod.impact-strength");
    private final Path root;
    private final Consumer<String> report;
    public ModeFiles(Path root) { this(root, message -> {}); }
    public ModeFiles(Path root, Consumer<String> report) { this.root = root.toAbsolutePath(); this.report = Objects.requireNonNull(report); }
    public static boolean editableProfileField(String key) { return GUI_PROFILE_FIELDS.contains(key); }
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
        Path items = root.resolve("classic/item.yml");
        if (!Files.exists(items)) copyResource("classic/item.yml", items);
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
            if (classic) {
                var items = ConfigCompletion.complete(directory.resolve("item.yml"), ConfigCompletion.resource("classic/item.yml"), false, true);
                drafts.add(items);
                var materials = ItemCombat.read(items.yaml());
                if (!materials.isEmpty()) {
                    // Validate all dependencies before completing any file or publishing a new snapshot.
                    var modernConfig = ConfigCompletion.complete(root.resolve("modern/config.yml"), ConfigCompletion.resource("modern/config.yml"), false, false);
                    var modernProfiles = ConfigCompletion.complete(root.resolve("modern/profiles.yml"), ConfigCompletion.resource("modern/profiles.yml"), true, false);
                    Settings modern = Settings.read(modernConfig.yaml(), modernProfiles.yaml());
                    drafts.add(modernConfig); drafts.add(modernProfiles);
                    candidate = candidate.withItems(new ItemCombat(materials, modern));
                }
            }
            ConfigCompletion.saveValidated(root, drafts, report);
            return candidate;
        } catch (IOException ex) { throw new IllegalStateException("Cannot complete " + mode.key() + " configuration; previous runtime state retained", ex); }
    }
    public synchronized Settings editProfile(CombatMode mode, String profile, String key, Object value) throws IOException {
        Objects.requireNonNull(mode); Objects.requireNonNull(profile); Objects.requireNonNull(key); Objects.requireNonNull(value);
        if (!profile.matches("[a-zA-Z0-9_-]+") || !GUI_PROFILE_FIELDS.contains(key))
            throw new IllegalArgumentException("Unsupported GUI profile field");
        Path file = root.resolve(mode.key()).resolve("profiles.yml");
        byte[] original = Files.readAllBytes(file);
        var yaml = YamlFiles.load(file);
        String target = profile.equals("defaults") ? "defaults" : "profiles." + profile;
        if (!yaml.isConfigurationSection(target)) throw new IllegalArgumentException("Unknown profile: " + profile);
        yaml.set(target + "." + key, value);
        try {
            YamlFiles.save(file, yaml);
            Settings candidate = load(mode);
            if (!profile.equals("defaults") && !candidate.profiles().containsKey(profile))
                throw new IllegalArgumentException("Unknown profile: " + profile);
            return candidate;
        } catch (RuntimeException | IOException failure) {
            try { YamlFiles.write(file, new String(original, java.nio.charset.StandardCharsets.UTF_8)); }
            catch (IOException restore) { failure.addSuppressed(restore); }
            if (failure instanceof IOException io) throw io;
            throw failure;
        }
    }
    public void save(CombatMode mode) throws IOException {
        var yaml = new YamlConfiguration();
        yaml.set("mode", mode.key());
        YamlFiles.save(root.resolve("mode.yml"), yaml);
    }
}

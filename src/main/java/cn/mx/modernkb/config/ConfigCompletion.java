package cn.mx.modernkb.config;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Builds missing-only drafts; callers validate the entire mode before any disk write. */
final class ConfigCompletion {
    record Draft(Path file, String original, YamlConfiguration yaml, int added) {
        boolean changed() { return original == null || added > 0; }
    }
    private ConfigCompletion() {}

    static YamlConfiguration parse(String text, String label) {
        var yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        try {
            yaml.loadFromString(text);
            // Bukkit drops comments when a document has no mapping entries to attach them to.
            if (yaml.getKeys(false).isEmpty() && yaml.options().getHeader().isEmpty() && yaml.options().getFooter().isEmpty()) {
                var header = text.lines().map(String::stripLeading).filter(line -> line.startsWith("#"))
                        .map(line -> line.substring(1).stripLeading()).toList();
                if (!header.isEmpty()) yaml.options().setHeader(header);
            }
            return yaml;
        }
        catch (Exception ex) { throw new IllegalArgumentException("Cannot load " + label + "; file preserved", ex); }
    }
    static YamlConfiguration resource(String name) throws IOException {
        try (var stream = ConfigCompletion.class.getResourceAsStream("/" + name)) {
            if (stream == null) throw new IOException("Missing bundled resource: " + name);
            return parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8), name);
        }
    }
    static Draft complete(Path file, YamlConfiguration template, boolean profiles, boolean classic) throws IOException {
        String original = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        var yaml = parse(original == null ? "" : original, file.toString());
        var definitions = yaml.getConfigurationSection("profiles");
        Set<String> existingProfiles = profiles && definitions != null ? Set.copyOf(definitions.getKeys(false)) : Set.of();
        Map<String, Object> inherited = new HashMap<>();
        var defaults = yaml.getConfigurationSection("defaults");
        if (profiles && defaults != null) for (String key : defaults.getKeys(true))
            if (!defaults.isConfigurationSection(key)) inherited.put(key, defaults.get(key));
        Predicate<String> allowed = path -> {
            if (path.equals("stop_sprint") && yaml.contains("reset-sprint-on-hit")) return false;
            if (!profiles || !path.startsWith("profiles.")) return true;
            String[] parts = path.split("\\.", 3);
            if (!existingProfiles.contains(parts[1]) || parts.length < 3) return true;
            // Materialize the classic rod section, retaining any explicitly inherited old values.
            if (classic && parts[1].equals("fishing_rod")) return true;
            // Other existing profiles keep inheriting defaults instead of gaining template overrides.
            return !yaml.contains("defaults." + parts[2]);
        };
        int added = 0;
        for (String path : template.getKeys(true)) {
            boolean section = template.isConfigurationSection(path);
            if (section && !template.getConfigurationSection(path).getKeys(false).isEmpty()) continue;
            if (yaml.contains(path) || !allowed.test(path) || blockedParent(yaml, path) || horizontalAlias(yaml, path)) continue;
            boolean existingRod = profiles && classic && existingProfiles.contains("fishing_rod") && path.startsWith("profiles.fishing_rod.");
            String inheritedPath = existingRod ? path.substring("profiles.fishing_rod.".length()) : "";
            if (existingRod && inheritedPath.equals("classic.horizontal")
                    && (inherited.containsKey("classic.horizontal-ground") || inherited.containsKey("classic.horizontal-air"))) {
                for (String axis : List.of("classic.horizontal-ground", "classic.horizontal-air")) {
                    String target = "profiles.fishing_rod." + axis;
                    if (inherited.containsKey(axis) && !yaml.contains(target)) { yaml.set(target, inherited.get(axis)); added++; }
                }
                continue; // A shorthand must not replace independently configured old ground/air axes.
            }
            List<String> missingParents = new ArrayList<>();
            for (int dot = path.indexOf('.'); dot >= 0; dot = path.indexOf('.', dot + 1)) {
                String parent = path.substring(0, dot);
                if (!yaml.contains(parent)) missingParents.add(parent);
            }
            if (section) yaml.createSection(path);
            else yaml.set(path, existingRod && inherited.containsKey(inheritedPath) ? inherited.get(inheritedPath) : template.get(path));
            for (String parent : missingParents) comments(template, yaml, parent);
            comments(template, yaml, path);
            added++;
        }
        return new Draft(file, original, yaml, added);
    }
    private static boolean blockedParent(YamlConfiguration yaml, String path) {
        for (int dot = path.indexOf('.'); dot >= 0; dot = path.indexOf('.', dot + 1)) {
            String parent = path.substring(0, dot);
            if (yaml.contains(parent) && !yaml.isConfigurationSection(parent)) return true;
        }
        return false;
    }
    private static boolean horizontalAlias(YamlConfiguration yaml, String path) {
        if (!path.endsWith("classic.horizontal")) return false;
        String prefix = path.substring(0, path.lastIndexOf('.') + 1);
        return yaml.contains(prefix + "horizontal-ground") || yaml.contains(prefix + "horizontal-air");
    }
    private static void comments(YamlConfiguration from, YamlConfiguration to, String path) {
        to.setComments(path, from.getComments(path));
        to.setInlineComments(path, from.getInlineComments(path));
    }
    static void saveValidated(Path root, List<Draft> drafts, Consumer<String> report) throws IOException {
        List<Draft> changed = drafts.stream().filter(Draft::changed).toList();
        if (changed.isEmpty()) return;
        // Detect a concurrent editor before creating backups or replacing any file.
        for (Draft draft : changed) {
            String current = Files.exists(draft.file) ? Files.readString(draft.file, StandardCharsets.UTF_8) : null;
            if (!Objects.equals(current, draft.original)) throw new IOException("Configuration changed during completion: " + draft.file);
        }
        String stamp = UUID.randomUUID().toString();
        Map<Draft, Path> backups = new LinkedHashMap<>();
        for (Draft draft : changed) if (draft.original != null) {
            Path backup = root.resolve("backups").resolve(root.relativize(draft.file) + ".before-completion-" + stamp + ".bak");
            Files.createDirectories(backup.getParent());
            Files.writeString(backup, draft.original, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            backups.put(draft, backup);
        }
        List<Draft> written = new ArrayList<>();
        try {
            for (Draft draft : changed) { YamlFiles.save(draft.file, draft.yaml); written.add(draft); }
        } catch (IOException ex) {
            Collections.reverse(written);
            for (Draft draft : written) try {
                if (draft.original == null) Files.deleteIfExists(draft.file);
                else YamlFiles.write(draft.file, draft.original);
            } catch (IOException rollback) { ex.addSuppressed(rollback); }
            throw ex;
        }
        for (Draft draft : changed) report.accept("Completed " + draft.added + " missing configuration entries in " + root.relativize(draft.file)
                + (backups.containsKey(draft) ? "; backup=" + backups.get(draft) : "; created new file"));
    }
}

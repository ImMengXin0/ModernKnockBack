package cn.mx.modernkb.config;

import cn.mx.modernkb.api.*;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** A validated immutable configuration, published atomically to all regions. */
public record Settings(boolean enabled, boolean affectOthers, boolean respectPlayerProfile,
                       boolean preserveSyncY, Set<String> botMetadata, Map<String, KnockbackProfile> profiles,
                       Map<String,String> routes, Map<String,String> matchups, CombatMode mode, ClassicSettings classic) {
    public Settings(boolean enabled, boolean affectOthers, boolean respectPlayerProfile, boolean preserveSyncY,
                    Set<String> botMetadata, Map<String,KnockbackProfile> profiles, Map<String,String> routes, Map<String,String> matchups) {
        this(enabled, affectOthers, respectPlayerProfile, preserveSyncY, botMetadata, profiles, routes, matchups,
                CombatMode.MODERN, ClassicSettings.defaults());
    }
    public Settings withMode(CombatMode mode, ClassicSettings classic) {
        return new Settings(enabled, affectOthers, respectPlayerProfile, preserveSyncY, botMetadata, profiles, routes, matchups, mode, classic);
    }
    public boolean classicEnabled() { return enabled && mode == CombatMode.CLASSIC; }
    public static Settings load(Path directory) {
        var config = YamlFiles.load(directory.resolve("config.yml"));
        var yaml = YamlFiles.load(directory.resolve("profiles.yml"));
        return read(config, yaml);
    }
    static Settings read(YamlConfiguration config, YamlConfiguration yaml) {
        checkConfigKeys(config);
        checkProfileKeys(yaml);
        if (!(config.get("config-version") instanceof Number n) || n.doubleValue() != 1) throw new IllegalArgumentException("Unsupported config-version");
        var defaults = section(yaml,"defaults");
        var definitions = section(yaml,"profiles");
        var profiles = new HashMap<String,KnockbackProfile>();
        for (String name : definitions.getKeys(false)) {
            if (!name.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Invalid profile name: " + name);
            var merged = new YamlConfiguration();
            merge(defaults, merged);
            merge(section(definitions,name), merged);
            profiles.put(name,KnockbackProfile.read(merged));
        }
        if (!profiles.containsKey("default")) throw new IllegalArgumentException("Missing default profile");
        var routes = mapping(section(config,"profiles"),profiles);
        var matchups = mapping(section(config,"matchups"),profiles);
        Set<String> types = new HashSet<>();
        for (HitType type : HitType.values()) types.add(type.key());
        types.add("bot-melee");
        if (!types.containsAll(routes.keySet())) throw new IllegalArgumentException("Unknown hit type in profiles routes");
        for (String key : matchups.keySet()) {
            String[] parts=key.split("\\.");
            if (parts.length!=3 || !types.contains(parts[0])) throw new IllegalArgumentException("Invalid matchup: "+key);
            for(int i=1;i<3;i++) CombatantKind.valueOf(parts[i].toUpperCase(Locale.ROOT));
        }
        if (!config.isString("compatibility.knockbacksync")) throw new IllegalArgumentException("Missing KnockbackSync policy");
        String sync = config.getString("compatibility.knockbacksync");
        if (!Set.of("preserve-y","before-sync").contains(sync)) throw new IllegalArgumentException("Invalid KnockbackSync policy");
        Object metadata = config.get("bot-metadata");
        if (!(metadata instanceof List<?> list) || list.stream().anyMatch(v -> !(v instanceof String)))
            throw new IllegalArgumentException("bot-metadata must be a string list");
        return new Settings(KnockbackProfile.bool(config,"enabled",true),
                KnockbackProfile.bool(config,"affect-other-living-entities",false),
                KnockbackProfile.bool(config,"respect-player-profile",true), sync.equals("preserve-y"),
                Set.copyOf(config.getStringList("bot-metadata")), Map.copyOf(profiles),Map.copyOf(routes),Map.copyOf(matchups));
    }
    private static ConfigurationSection section(ConfigurationSection yaml,String path) {
        var section=yaml.getConfigurationSection(path);
        if(section==null) throw new IllegalArgumentException("Missing/invalid section: "+path);
        return section;
    }
    private static void checkConfigKeys(ConfigurationSection c) {
        requireKeys(c, Set.of("config-version", "enabled", "affect-other-living-entities", "respect-player-profile",
                "bot-metadata", "compatibility", "profiles", "matchups"), "config");
        var compatibility = section(c, "compatibility");
        requireKeys(compatibility, Set.of("knockbacksync"), "compatibility");
    }
    private static void checkProfileKeys(ConfigurationSection c) {
        requireKeys(c, Set.of("defaults", "profiles"), "profiles.yml");
        checkProfile(section(c, "defaults"));
        for (String name : section(c, "profiles").getKeys(false)) checkProfile(section(c, "profiles." + name));
    }
    private static void checkProfile(ConfigurationSection profile) {
        Set<String> sections = Set.of("horizontal", "vertical", "modern", "limits", "rod", "native", "classic");
        Set<String> values = Set.of("enabled", "horizontal.ground", "horizontal.air", "vertical.ground", "vertical.air",
                "modern.sprint-multiplier", "modern.critical-multiplier", "modern.weak-hit-multiplier", "limits.horizontal",
                "limits.upward", "limits.downward", "rod.enabled", "rod.cast-interval-ticks", "rod.recast-delay-ticks",
                "rod.horizontal-speed-multiplier", "rod.vertical-speed-multiplier", "rod.max-distance", "rod.max-lifetime-ticks", "rod.impact-strength",
                "native.enabled", "native.horizontal-retention-ground", "native.horizontal-retention-air",
                "native.vertical-retention-ground", "native.vertical-max-ground", "native.air-vertical-mode", "native.vertical-max-air",
                "classic.horizontal", "classic.horizontal-ground", "classic.horizontal-air", "classic.vertical-ground", "classic.vertical-air",
                "classic.horizontal-retention", "classic.vertical-retention", "classic.sprint-horizontal", "classic.sprint-vertical",
                "classic.enchantment-horizontal", "classic.vertical-max", "classic.respect-resistance", "classic.y-limit-enabled",
                "classic.max-height", "classic.vertical-after-limit");
        for (String key : profile.getKeys(true)) {
            if (sections.contains(key)) {
                if (!profile.isConfigurationSection(key)) throw new IllegalArgumentException("Expected section: " + profile.getCurrentPath() + "." + key);
            } else if (!values.contains(key) || profile.isConfigurationSection(key)) {
                throw new IllegalArgumentException("Unknown/invalid profile field: " + profile.getCurrentPath() + "." + key);
            }
        }
    }
    private static void requireKeys(ConfigurationSection section, Set<String> allowed, String path) {
        for (String key : section.getKeys(false)) if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown key " + path + "." + key);
    }
    private static void merge(ConfigurationSection from,YamlConfiguration to) {
        // Expand the shorthand at each inheritance layer; a per-profile value overrides both defaults.
        if (from.contains("classic.horizontal")) {
            to.set("classic.horizontal-ground", from.get("classic.horizontal"));
            to.set("classic.horizontal-air", from.get("classic.horizontal"));
        }
        for(String key:from.getKeys(true)) if(!from.isConfigurationSection(key)) to.set(key,from.get(key));
    }
    private static Map<String,String> mapping(ConfigurationSection section, Map<String,KnockbackProfile> profiles) {
        var result=new HashMap<String,String>();
        for(String key:section.getKeys(true)) if(!section.isConfigurationSection(key)) {
            if(!(section.get(key) instanceof String value) || !profiles.containsKey(value))
                throw new IllegalArgumentException("Unknown KB profile at "+section.getCurrentPath()+"."+key);
            result.put(key,value);
        }
        return result;
    }
    public String select(HitContext hit, String assigned) {
        String exact=matchups.get(hit.type().key()+"."+hit.attackerKind().key()+"."+hit.victimKind().key());
        if(exact!=null) return exact;
        if(respectPlayerProfile && !assigned.equals("default") && profiles.containsKey(assigned)) return assigned;
        String category=hit.type()==HitType.MELEE && (hit.attackerKind()==CombatantKind.BOT || hit.victimKind()==CombatantKind.BOT)
                ? "bot-melee":hit.type().key();
        return routes.getOrDefault(category,"default");
    }
    public String rodProfile(String assigned) {
        if(respectPlayerProfile && !assigned.equals("default") && profiles.containsKey(assigned)) return assigned;
        return routes.getOrDefault("fishing-rod","default");
    }
}

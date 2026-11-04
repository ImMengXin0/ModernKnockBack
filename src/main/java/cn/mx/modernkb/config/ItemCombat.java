package cn.mx.modernkb.config;

import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/** Immutable item exceptions and the modern configuration loaded in the same transaction. */
public record ItemCombat(Set<String> modernItems, Settings modern) {
    public ItemCombat { modernItems = Set.copyOf(modernItems); }
    public static ItemCombat empty() { return new ItemCombat(Set.of(), null); }
    public boolean contains(String material) { return modern != null && modernItems.contains(material); }
    public static Set<String> read(ConfigurationSection yaml) {
        if (!yaml.getKeys(false).equals(Set.of("modern-items")))
            throw new IllegalArgumentException("classic/item.yml expects modern-items: [MACE, ...]");
        Object value = yaml.get("modern-items");
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("modern-items must be a material list");
        Set<String> items = new LinkedHashSet<>();
        for (Object entry : list) {
            if (!(entry instanceof String text)) throw new IllegalArgumentException("modern-items entries must be material names");
            String name = text.trim().toUpperCase(Locale.ROOT);
            if (name.startsWith("MINECRAFT:")) name = name.substring(10);
            Material material = Material.getMaterial(name);
            if (material == null || name.startsWith("LEGACY_") || Set.of("AIR", "CAVE_AIR", "VOID_AIR").contains(name))
                throw new IllegalArgumentException("Unknown/invalid material in classic/item.yml: " + text);
            items.add(name);
        }
        return Set.copyOf(items);
    }
}

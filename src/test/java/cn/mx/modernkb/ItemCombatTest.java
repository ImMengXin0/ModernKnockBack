package cn.mx.modernkb;

import cn.mx.modernkb.api.*;
import cn.mx.modernkb.config.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ItemCombatTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path root;
    private ModeFiles files;
    @Before public void setup() throws Exception {
        root = temporary.getRoot().toPath(); files = new ModeFiles(root); files.initialize();
    }
    @Test public void defaultsContainEvery12111SpearAndMaceAndModernIsIndependent() {
        Settings classic = files.load(CombatMode.CLASSIC);
        Set<String> expected = new HashSet<>(); expected.add("MACE");
        Arrays.stream(Material.values()).map(Enum::name).filter(n -> n.endsWith("_SPEAR")).forEach(expected::add);
        assertEquals(8, expected.size()); assertEquals(expected, classic.items().modernItems());
        assertNotNull(classic.items().modern()); assertEquals(CombatMode.MODERN, classic.items().modern().mode());
        assertFalse(files.load(CombatMode.MODERN).modernItem("MACE"));
    }
    @Test public void removedItemsAndEmptyListsSurviveReloadAndRestart() throws Exception {
        Path path = root.resolve("classic/item.yml");
        Files.writeString(path, "modern-items: [MACE]\n");
        assertEquals(Set.of("MACE"), files.load(CombatMode.CLASSIC).items().modernItems());
        files.initialize();
        assertEquals(Set.of("MACE"), new ModeFiles(root).load(CombatMode.CLASSIC).items().modernItems());
        Files.writeString(path, "modern-items: []\n");
        Files.writeString(root.resolve("modern/config.yml"), "enabled: broken\n");
        assertTrue(files.load(CombatMode.CLASSIC).items().modernItems().isEmpty());
        assertEquals("modern-items: []\n", Files.readString(path));
    }
    @Test public void reloadReadsRealModernProfilesWithoutChangingClassicParameters() throws Exception {
        Settings before = files.load(CombatMode.CLASSIC);
        Path modern = root.resolve("modern/profiles.yml");
        var yaml = YamlFiles.load(modern); yaml.set("profiles.mace.horizontal.ground", 1.73); YamlFiles.save(modern, yaml);
        Settings after = files.load(CombatMode.CLASSIC);
        assertNotSame(before, after);
        assertEquals(1.73, after.items().modern().profiles().get("mace").groundHorizontal(), 0);
        assertEquals(before.profiles(), after.profiles());
    }
    @Test public void invalidItemsOrModernConfigDoNotCompleteOrReplaceFiles() throws Exception {
        Path item = root.resolve("classic/item.yml");
        for (String bad : List.of("modern-items: [MACEE]\n", "modern-items: [false]\n", "modern-items: MACE\n", "modern-items: [AIR]\n", "unexpected: []\n")) {
            Files.writeString(item, bad);
            assertThrows(IllegalArgumentException.class, () -> files.load(CombatMode.CLASSIC));
            assertEquals(bad, Files.readString(item));
        }
        Files.writeString(item, "modern-items: [MACE]\n");
        Files.writeString(root.resolve("classic/combat.yml"), "hit-interval-ticks: 9\n");
        Files.writeString(root.resolve("modern/profiles.yml"), "defaults: {horizontal: {ground: -1}}\nprofiles: {default: {}}\n");
        assertThrows(IllegalArgumentException.class, () -> files.load(CombatMode.CLASSIC));
        assertEquals("hit-interval-ticks: 9\n", Files.readString(root.resolve("classic/combat.yml")));
    }
    @Test public void normalizesMaterialNamesAndCompletesMissingFile() throws Exception {
        Files.delete(root.resolve("classic/item.yml"));
        assertEquals(8, files.load(CombatMode.CLASSIC).items().modernItems().size());
        assertTrue(Files.exists(root.resolve("classic/item.yml")));
        var yaml = new YamlConfiguration(); yaml.set("modern-items", List.of("minecraft:mace", "iron_spear", "MACE"));
        assertEquals(Set.of("MACE", "IRON_SPEAR"), ItemCombat.read(yaml));
    }
    @Test public void routingRequiresDirectWeaponHitAndRetainsOldApiConstructor() {
        Settings classic = files.load(CombatMode.CLASSIC);
        UUID a = UUID.randomUUID(), v = UUID.randomUUID();
        var hit = new HitContext(v, a, a, HitType.MACE, CombatantKind.PLAYER, CombatantKind.PLAYER,
                "ENTITY_ATTACK", true, false, false, .25, false, "MACE");
        assertSame(classic.items().modern(), classic.combatSettings(hit));
        assertEquals("mace", classic.combatSettings(hit).select(hit, "default"));
        var arrow = new HitContext(v, a, UUID.randomUUID(), HitType.ARROW, CombatantKind.PLAYER, CombatantKind.PLAYER,
                "DAMAGE", true, false, false, 1, false, "MACE");
        assertSame(classic, classic.combatSettings(arrow));
        var old = new HitContext(v, a, a, HitType.MACE, CombatantKind.PLAYER, CombatantKind.PLAYER,
                "ENTITY_ATTACK", true, false, false, 1, false);
        assertSame(classic, classic.combatSettings(old));
    }
}

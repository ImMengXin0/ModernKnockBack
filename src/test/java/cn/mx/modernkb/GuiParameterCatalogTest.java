package cn.mx.modernkb;

import cn.mx.modernkb.api.CombatMode;
import cn.mx.modernkb.config.KnockbackProfile;
import cn.mx.modernkb.config.ModeFiles;
import cn.mx.modernkb.gui.GuiParameterCatalog;
import org.junit.Test;
import static org.junit.Assert.*;

public class GuiParameterCatalogTest {
    @Test public void eachModeHasDocumentedProfileFieldsWithinInventoryPageCapacity() {
        for (var mode : CombatMode.values()) for (var section : GuiParameterCatalog.sections()) {
            var parameters = GuiParameterCatalog.parameters(mode, section);
            assertFalse(parameters.isEmpty());
            assertTrue("Parameter page too large: " + mode + "/" + section, parameters.size() <= 12);
            for (var parameter : parameters) {
                assertFalse(parameter.path(), parameter.path().isBlank());
                assertFalse(parameter.label(), parameter.label().isBlank());
                assertFalse(parameter.explanation(), parameter.explanation().isBlank());
                assertTrue(GuiParameterCatalog.editablePaths().contains(parameter.path()));
                assertTrue(ModeFiles.editableProfileField(parameter.path()));
            }
        }
    }
    @Test public void numericAdjustmentsUseSmallAndShiftStepsAndClampToProfileBounds() {
        var classic = find(CombatMode.CLASSIC, GuiParameterCatalog.Section.CORE, "classic.horizontal-ground");
        assertEquals(.45, classic.change(.4, 1));
        assertEquals(0.0, classic.change(0.02, -1));
        assertEquals(4.0, classic.change(3.99, 1));
        var gravity = find(CombatMode.MODERN, GuiParameterCatalog.Section.ROD, "rod.gravity-multiplier");
        assertEquals(1.0, gravity.change(1.0, -1));
        assertEquals(1.1, gravity.change(1.0, 1));
    }
    @Test public void togglesAndNativeAirChoiceCycleWithoutInventingConfigPaths() {
        var enabled = find(CombatMode.CLASSIC, GuiParameterCatalog.Section.CORE, "enabled");
        assertEquals(Boolean.FALSE, enabled.change(true, 1));
        assertEquals(Boolean.TRUE, enabled.change(false, 1));
        var air = find(CombatMode.MODERN, GuiParameterCatalog.Section.ADVANCED, "native.air-vertical-mode");
        assertEquals("add", air.change("preserve", 1));
        assertEquals("preserve", air.change("add", -1));
        assertEquals("native.air-vertical-mode", air.path());
    }
    @Test public void currentProfileValueReadersMatchModernAndClassicSchemas() {
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("classic.horizontal-ground", .7);
        yaml.set("modern.sprint-multiplier", 1.25);
        yaml.set("rod.gravity-multiplier", 1.8);
        var profile = KnockbackProfile.read(yaml);
        assertEquals(.7, find(CombatMode.CLASSIC, GuiParameterCatalog.Section.CORE,
                "classic.horizontal-ground").value(profile));
        assertEquals(1.25, find(CombatMode.MODERN, GuiParameterCatalog.Section.CORE,
                "modern.sprint-multiplier").value(profile));
        assertEquals(1.8, find(CombatMode.CLASSIC, GuiParameterCatalog.Section.ROD,
                "rod.gravity-multiplier").value(profile));
    }
    @Test public void pluginDescriptorRegistersTheRequestedKbAlias() throws Exception {
        try (var reader = new java.io.InputStreamReader(getClass().getResourceAsStream("/plugin.yml"))) {
            var plugin = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(reader);
            var command = plugin.getConfigurationSection("commands.modernkb");
            assertTrue(command.getStringList("aliases").contains("kb"));
            assertEquals("modernkb.admin", command.getString("permission"));
        }
    }
    private static GuiParameterCatalog.Parameter find(CombatMode mode, GuiParameterCatalog.Section section, String path) {
        return GuiParameterCatalog.parameters(mode, section).stream().filter(parameter -> parameter.path().equals(path))
                .findFirst().orElseThrow(() -> new AssertionError("Missing GUI field " + mode + "/" + path));
    }
}

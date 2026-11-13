package cn.mx.modernkb.profiler;

import cn.mx.modernkb.api.CombatMode;
import cn.mx.modernkb.config.ClassicSettings;
import cn.mx.modernkb.config.ItemCombat;
import cn.mx.modernkb.config.KnockbackProfile;
import cn.mx.modernkb.config.Settings;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProfilerConfigurationDataTest {
    private static KnockbackProfile profile(double horizontal) {
        return new KnockbackProfile(true, horizontal, .9, .8, .7, 1.1, 1.2, 1.3, 0, 0, 0,
                new KnockbackProfile.Rod(true, 3, 1, 1.1, 1, 24, 0, .4, 1.6),
                new KnockbackProfile.Native(true, .55, .45, .5, .4, true, .42), KnockbackProfile.Classic.defaults());
    }

    private static Settings settings(CombatMode mode, double horizontal) {
        return new Settings(true, true, false, true, Set.of("NPC", "Bot"), Map.of("player", profile(horizontal)),
                Map.of("melee", "player"), Map.of("arrow.player.bot", "player"), mode,
                new ClassicSettings(10, 4, true, true, false, true, 2, 6, true, true, true));
    }

    @Test public void snapshotsAllEffectiveProfileFieldsAndModernItemDependency() {
        Settings modern = settings(CombatMode.MODERN, 1.37);
        Settings classic = settings(CombatMode.CLASSIC, 1.0).withItems(new ItemCombat(Set.of("MACE", "WOODEN_SPEAR"), modern));
        var values = ProfilerConfiguration.snapshot(classic);
        assertEquals("classic", values.get("mode.yml.mode"));
        assertEquals("player", values.get("config.yml.profiles.melee"));
        assertEquals("player", values.get("config.yml.matchups.arrow.player.bot"));
        assertEquals(List.of("Bot", "NPC"), values.get("config.yml.bot-metadata"));
        assertEquals(List.of("MACE", "WOODEN_SPEAR"), values.get("item.yml.modern-items"));
        assertEquals(10, values.get("effective.classic.rod-hit-interval-ticks"));
        assertEquals(10, values.get("effective.classic.rod-melee-interval-ticks"));
        assertEquals(1.6, values.get("profiles.yml.profiles.player.rod.gravity-multiplier"));
        assertEquals("add", values.get("profiles.yml.profiles.player.native.air-vertical-mode"));
        assertEquals(.36075, values.get("profiles.yml.profiles.player.classic.vertical-ground"));
        assertEquals(1.37, values.get("item-modern.profiles.yml.profiles.player.horizontal.ground"));
        assertEquals("modern", values.get("item-modern.mode.yml.mode"));
        assertEquals(recordLeaves(KnockbackProfile.class), values.keySet().stream().filter(k -> k.startsWith("profiles.yml.profiles.player.")).count());
        assertEquals(recordLeaves(KnockbackProfile.class), values.keySet().stream().filter(k -> k.startsWith("item-modern.profiles.yml.profiles.player.")).count());
        assertThrows(UnsupportedOperationException.class, () -> values.put("mutate", true));
    }

    @Test public void everyExportedParameterHasDescriptionAndDoesNotUseRawFileValues() {
        var values = ProfilerConfiguration.snapshot(settings(CombatMode.CLASSIC, 1));
        var descriptions = ProfilerConfiguration.descriptions(values);
        assertEquals(values.keySet(), descriptions.keySet());
        for (String description : descriptions.values()) {
            assertNotNull(description);
            assertFalse(description.isBlank());
            assertNotEquals("运行时已经验证并生效的参数。", description);
        }
        assertTrue(descriptions.get("profiles.yml.profiles.player.classic.respect-resistance").contains("下界合金"));
        assertEquals(4, values.get("combat.yml.rod-hit-interval-ticks"));
        assertEquals(10, values.get("effective.classic.rod-hit-interval-ticks"));
    }

    private static long recordLeaves(Class<?> type) {
        if (!type.isRecord()) return 1;
        long count = 0;
        for (var component : type.getRecordComponents()) count += recordLeaves(component.getType());
        return count;
    }
}

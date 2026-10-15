package cn.mx.modernkb;

import cn.mx.modernkb.api.CombatMode;
import cn.mx.modernkb.combat.PlayerPushRules;
import cn.mx.modernkb.config.*;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.*;
import org.bukkit.persistence.*;
import org.bukkit.plugin.Plugin;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PlayerPushRulesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private MockedStatic<Bukkit> bukkit;
    private Plugin plugin;
    private Player first, second;
    private Settings settings;
    private PlayerStore store;
    private PlayerPushRules rules;
    private final Map<Player, Map<NamespacedKey, Object>> data = new HashMap<>();
    private NamespacedKey legacy, owned;
    @Before public void setup() {
        bukkit = mockStatic(Bukkit.class);
        plugin = mock(Plugin.class); when(plugin.namespace()).thenReturn("modernknockback");
        when(plugin.getName()).thenReturn("ModernKnockback");
        first = player(); second = player();
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(first, second));
        store = new PlayerStore(temporary.getRoot().toPath().resolve("players.yml"));
        settings = new Settings(true, false, true, false, Set.of(), Map.of(), Map.of(), Map.of())
                .withMode(CombatMode.CLASSIC, ClassicSettings.defaults());
        legacy = new NamespacedKey(plugin, "classic_previous_collision");
        owned = new NamespacedKey(plugin, "classic_push_exemptions");
        rules = new PlayerPushRules(plugin, () -> settings, store);
    }
    private Player player() {
        Player player = mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(true);
        bukkit.when(() -> Bukkit.getPlayer(player.getUniqueId())).thenReturn(player);
        AtomicBoolean collidable = new AtomicBoolean(true);
        when(player.isCollidable()).thenAnswer(call -> collidable.get());
        doAnswer(call -> { collidable.set(call.getArgument(0)); return null; }).when(player).setCollidable(anyBoolean());
        when(player.getCollidableExemptions()).thenReturn(new HashSet<>());
        var values = new HashMap<NamespacedKey, Object>(); data.put(player, values);
        PersistentDataContainer container = mock(PersistentDataContainer.class);
        when(player.getPersistentDataContainer()).thenReturn(container);
        when(container.get(any(), eq(PersistentDataType.INTEGER))).thenAnswer(call -> values.get(call.getArgument(0)));
        when(container.get(any(), eq(PersistentDataType.STRING))).thenAnswer(call -> values.get(call.getArgument(0)));
        doAnswer(call -> { values.put(call.getArgument(0), call.getArgument(2)); return null; })
                .when(container).set(any(), eq(PersistentDataType.STRING), any());
        doAnswer(call -> { values.remove(call.getArgument(0)); return null; }).when(container).remove(any());
        EntityScheduler scheduler = mock(EntityScheduler.class); when(player.getScheduler()).thenReturn(scheduler);
        doAnswer(call -> { ((java.util.function.Consumer) call.getArgument(1)).accept(null); return null; })
                .when(scheduler).run(any(), any(), any());
        return player;
    }
    @After public void close() { bukkit.close(); }
    @Test public void classicExcludesOtherPlayersWithoutDisablingProjectileCollisions() {
        rules.reconcile(first); rules.reconcile(second);
        assertTrue(first.isCollidable()); assertTrue(second.isCollidable());
        assertEquals(Set.of(second.getUniqueId()), first.getCollidableExemptions());
        assertEquals(Set.of(first.getUniqueId()), second.getCollidableExemptions());
        verify(first, never()).setCollidable(anyBoolean()); verify(second, never()).setCollidable(anyBoolean());
    }
    @Test public void modernRemovesOnlyOwnedEntriesAndKeepsExternalExemptions() {
        UUID external = UUID.randomUUID(); first.getCollidableExemptions().add(external);
        rules.reconcile(first); rules.reconcile(first);
        settings = settings.withMode(CombatMode.MODERN, ClassicSettings.defaults()); rules.reconcile(first);
        assertEquals(Set.of(external), first.getCollidableExemptions()); assertTrue(data.get(first).isEmpty());
    }
    @Test public void existingPlayerExemptionsAreNotClaimedOrRemoved() {
        first.getCollidableExemptions().add(second.getUniqueId());
        rules.reconcile(first); rules.close();
        assertEquals(Set.of(second.getUniqueId()), first.getCollidableExemptions());
    }
    @Test public void upgradeRecovers128CollisionFlagEvenWhenStillClassic() {
        first.setCollidable(false); data.get(first).put(legacy, 1);
        rules.reconcile(first);
        assertTrue(first.isCollidable()); assertFalse(data.get(first).containsKey(legacy));
        assertTrue(first.getCollidableExemptions().contains(second.getUniqueId()));
    }
    @Test public void originallyDisabledExternalCollisionIsPreserved() {
        first.setCollidable(false); data.get(first).put(legacy, 0);
        rules.reconcile(first);
        assertFalse(first.isCollidable()); assertTrue(first.getCollidableExemptions().isEmpty());
    }
    @Test public void restartRecoversPersistedOwnedEntriesAndRetainsUnrelatedEntries() {
        UUID stale = UUID.randomUUID(), external = UUID.randomUUID();
        first.getCollidableExemptions().addAll(Set.of(stale, external));
        data.get(first).put(owned, stale.toString());
        rules.reconcile(first);
        assertEquals(Set.of(second.getUniqueId(), external), first.getCollidableExemptions());
        rules.close(); assertEquals(Set.of(external), first.getCollidableExemptions());
    }
    @Test public void joinsAndQuitsUpdatePairsWithoutLeavingOfflineIds() {
        rules.refreshAll();
        var quit = mock(PlayerQuitEvent.class); when(quit.getPlayer()).thenReturn(second); rules.quit(quit);
        assertTrue(first.getCollidableExemptions().isEmpty()); assertTrue(second.getCollidableExemptions().isEmpty());
        var join = mock(PlayerJoinEvent.class); when(join.getPlayer()).thenReturn(second); rules.join(join);
        assertTrue(first.getCollidableExemptions().contains(second.getUniqueId()));
        assertTrue(second.getCollidableExemptions().contains(first.getUniqueId()));
    }
    @Test public void exclusionAndForeignRegionsDoNotApplyNewRules() {
        rules.reconcile(first); store.excluded(first.getUniqueId(), true); rules.reconcile(first);
        assertTrue(first.getCollidableExemptions().isEmpty());
        store.excluded(first.getUniqueId(), false);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(first)).thenReturn(false); rules.reconcile(first);
        assertTrue(first.getCollidableExemptions().isEmpty());
    }
}

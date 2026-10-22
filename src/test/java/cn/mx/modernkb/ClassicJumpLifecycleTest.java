package cn.mx.modernkb;

import cn.mx.modernkb.api.*;
import cn.mx.modernkb.combat.*;
import cn.mx.modernkb.config.*;
import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.*;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.junit.*;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ClassicJumpLifecycleTest {
    private MockedStatic<Bukkit> bukkit;
    private ClassicCombat combat;
    private Player player;
    private CombatTracker tracker;
    private PlayerStore players;
    private Settings settings;
    private Input input;
    private final Vector motion = new Vector(0.8, 0.42, -0.2);

    @BeforeClass public static void registries() { ClassicCombatTest.bootstrapAttributes(); }
    @Before public void setup() {
        bukkit = mockStatic(Bukkit.class);
        Plugin plugin = mock(Plugin.class);
        when(plugin.namespace()).thenReturn("modernknockback");
        when(plugin.getName()).thenReturn("ModernKnockBack");
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getTicksLived()).thenReturn(100);
        when(player.getVelocity()).thenAnswer(call -> motion.clone());
        when(player.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        when(player.getScheduler()).thenReturn(mock(EntityScheduler.class));
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(true);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
        tracker = mock(CombatTracker.class);
        when(tracker.kind(player)).thenReturn(CombatantKind.PLAYER);
        players = mock(PlayerStore.class);
        when(players.get(player.getUniqueId())).thenReturn(new PlayerStore.Preference("default", false));
        settings = new Settings(true, false, true, true, Set.of(), Map.of("default", KnockbackMathTest.profile()), Map.of(), Map.of())
                .withMode(CombatMode.CLASSIC, ClassicSettings.defaults());
        combat = new ClassicCombat(plugin, () -> settings, players, tracker);
        input = mock(Input.class);
        when(input.isJump()).thenReturn(true);
    }
    @After public void close() { bukkit.close(); }
    private Location from() { return new Location(null, 0, 64, 0); }
    private Location to() { return new Location(null, 0.2, 64.42, -0.1); }
    private void accepted() {
        combat.input(new PlayerInputEvent(player, input));
        combat.jump(new PlayerJumpEvent(player, from(), to()));
        combat.move(new PlayerMoveEvent(player, from(), to()));
    }
    private double vertical() {
        var context = new HitContext(player.getUniqueId(), null, null, HitType.MELEE,
                CombatantKind.PLAYER, CombatantKind.PLAYER, "ENTITY_ATTACK", false, false, false, 1, false);
        var event = mock(EntityKnockbackEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getCause()).thenReturn(EntityKnockbackEvent.Cause.ENTITY_ATTACK);
        when(event.getKnockback()).thenReturn(new Vector(0.4, 0.24775, 0));
        var result = combat.calculate(event, context, KnockbackMathTest.profile(), settings);
        assertNotNull(result);
        return motion.clone().add(result.impulse()).getY();
    }
    @Test public void jumpEventsDriveCalculationAndFalseLiftKeepsOrdinaryAirVector() {
        combat.jump(new PlayerJumpEvent(player, from(), to()));
        combat.move(new PlayerMoveEvent(player, from(), to()));
        assertEquals(0.24775, vertical(), 1e-12);
        accepted();
        assertEquals(0.45775, vertical(), 1e-12);
    }
    @Test public void lateMovementCancellationCannotGrantHalfMomentum() {
        combat.input(new PlayerInputEvent(player, input));
        combat.jump(new PlayerJumpEvent(player, from(), to()));
        PlayerMoveEvent movement = new PlayerMoveEvent(player, from(), to());
        combat.move(movement);
        movement.setCancelled(true);
        assertEquals(0.24775, vertical(), 1e-12);
    }
    @Test public void excludedPlayersAndPlayerShapedBotsDiscardJumpEvidence() {
        accepted();
        when(players.get(player.getUniqueId())).thenReturn(new PlayerStore.Preference("default", true));
        assertEquals(0.24775, vertical(), 1e-12);
        when(players.get(player.getUniqueId())).thenReturn(new PlayerStore.Preference("default", false));
        assertEquals(0.24775, vertical(), 1e-12);
        accepted();
        when(tracker.kind(player)).thenReturn(CombatantKind.BOT);
        assertEquals(0.24775, vertical(), 1e-12);
        when(tracker.kind(player)).thenReturn(CombatantKind.PLAYER);
        assertEquals(0.24775, vertical(), 1e-12);
    }
    @Test public void flyingSwimmingGlidingAndVehiclesCannotGrantJumpReset() {
        accepted(); when(player.isFlying()).thenReturn(true);
        assertEquals(0.24775, vertical(), 1e-12);
        when(player.isFlying()).thenReturn(false);
        accepted(); when(player.isSwimming()).thenReturn(true);
        assertEquals(0.24775, vertical(), 1e-12);
        when(player.isSwimming()).thenReturn(false);
        accepted(); when(player.isGliding()).thenReturn(true);
        assertEquals(0.24775, vertical(), 1e-12);
        when(player.isGliding()).thenReturn(false);
        accepted(); when(player.isInsideVehicle()).thenReturn(true);
        assertEquals(0.24775, vertical(), 1e-12);
    }
    @Test public void reloadAndLifecycleChangesClearARecentJump() {
        accepted(); combat.refreshAll();
        assertEquals(0.24775, vertical(), 1e-12);
        accepted(); combat.teleport(new PlayerTeleportEvent(player, from(), to()));
        assertEquals(0.24775, vertical(), 1e-12);
        accepted();
        PlayerRespawnEvent respawn = mock(PlayerRespawnEvent.class); when(respawn.getPlayer()).thenReturn(player);
        combat.respawn(respawn);
        assertEquals(0.24775, vertical(), 1e-12);
        accepted();
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class); when(quit.getPlayer()).thenReturn(player);
        combat.quit(quit);
        assertEquals(0.24775, vertical(), 1e-12);
        accepted(); combat.close();
        assertEquals(0.24775, vertical(), 1e-12);
    }
    @Test public void modernEventsCannotLeaveEvidenceWhenReturningToClassic() {
        accepted();
        settings = settings.withMode(CombatMode.MODERN, ClassicSettings.defaults());
        combat.input(new PlayerInputEvent(player, input));
        combat.jump(new PlayerJumpEvent(player, from(), to()));
        combat.move(new PlayerMoveEvent(player, from(), to()));
        settings = settings.withMode(CombatMode.CLASSIC, ClassicSettings.defaults());
        assertEquals(0.24775, vertical(), 1e-12);
    }
    @Test public void disabledJumpResetClearsCandidateAndDoesNotRestoreItWhenEnabled() {
        accepted();
        settings = settings.withMode(CombatMode.CLASSIC, new ClassicSettings(10, 10, true, true, false, false, 2));
        combat.input(new PlayerInputEvent(player, input));
        combat.jump(new PlayerJumpEvent(player, from(), to()));
        settings = settings.withMode(CombatMode.CLASSIC, ClassicSettings.defaults());
        assertEquals(0.24775, vertical(), 1e-12);
    }
    @Test public void foreignRegionEventsDoNotReadOrMutatePlayerState() {
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(false);
        clearInvocations(player);
        combat.input(new PlayerInputEvent(player, input));
        combat.jump(new PlayerJumpEvent(player, from(), to()));
        combat.move(new PlayerMoveEvent(player, from(), to()));
        verifyNoInteractions(player);
    }
}

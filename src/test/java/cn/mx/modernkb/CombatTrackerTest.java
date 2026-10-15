package cn.mx.modernkb;

import cn.mx.modernkb.api.*;
import cn.mx.modernkb.combat.*;
import cn.mx.modernkb.config.*;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.junit.*;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CombatTrackerTest {
    private MockedStatic<Bukkit> bukkit;
    private CombatTracker tracker;
    private Player attacker, victim;
    private Settings settings;
    @Before public void setup() {
        bukkit = mockStatic(Bukkit.class);
        Plugin plugin = mock(Plugin.class); when(plugin.getName()).thenReturn("ModernKnockback"); when(plugin.namespace()).thenReturn("modernknockback");
        settings = new Settings(true, false, true, true, Set.of("NPC"), Map.of(), Map.of(), Map.of());
        tracker = new CombatTracker(plugin, () -> settings);
        attacker = player(); victim = player();
    }
    private Player player() {
        Player p = mock(Player.class); when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.getType()).thenReturn(EntityType.PLAYER); when(p.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(scheduler.run(any(), any(), any())).thenReturn(mock(ScheduledTask.class));
        when(p.getScheduler()).thenReturn(scheduler); when(p.getTicksLived()).thenReturn(10);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(p)).thenReturn(true); return p;
    }
    @After public void close() { bukkit.close(); }
    private void classic(boolean stopSprint) {
        settings = settings.withMode(CombatMode.CLASSIC, new ClassicSettings(10, 4, true, true, stopSprint, true, 2));
    }
    @Test public void classicHeldClientSprintSurvivesNativeResetAcrossMultipleHits() {
        classic(false);
        tracker.toggleSprint(new PlayerToggleSprintEvent(attacker, true));
        when(attacker.isSprinting()).thenReturn(true);
        for (int tick : new int[]{10, 20, 30, 40}) {
            when(attacker.getTicksLived()).thenReturn(tick);
            tracker.preAttack(new PrePlayerAttackEntityEvent(attacker, victim, true));
            when(attacker.isSprinting()).thenReturn(false); // Native attack resets entity sprint.
            assertTrue(tracker.context(victim, attacker, "ENTITY_ATTACK", false).sprinting());
        }
        verify(attacker, never()).setSprinting(anyBoolean());
    }
    @Test public void classicFirstAttackSeedsHeldSprintWhenNoToggleWasObserved() {
        classic(false);
        when(attacker.isSprinting()).thenReturn(true);
        tracker.preAttack(new PrePlayerAttackEntityEvent(attacker, victim, true));
        when(attacker.isSprinting()).thenReturn(false);
        when(attacker.getTicksLived()).thenReturn(20);
        tracker.preAttack(new PrePlayerAttackEntityEvent(attacker, victim, true));
        assertTrue(tracker.source(attacker, victim.getUniqueId()).sprint());
    }
    @Test public void classicWtapAndStapFollowRealClientStopAndStartActions() {
        classic(false);
        tracker.toggleSprint(new PlayerToggleSprintEvent(attacker, true));
        assertTrue(tracker.source(attacker, victim.getUniqueId()).sprint());
        tracker.toggleSprint(new PlayerToggleSprintEvent(attacker, false));
        when(attacker.isSprinting()).thenReturn(true); // Server has not applied STOP yet.
        assertFalse(tracker.source(attacker, victim.getUniqueId()).sprint());
        tracker.preAttack(new PrePlayerAttackEntityEvent(attacker, victim, true));
        assertFalse(tracker.context(victim, attacker, "ENTITY_ATTACK", false).sprinting());
        when(attacker.getTicksLived()).thenReturn(20);
        tracker.toggleSprint(new PlayerToggleSprintEvent(attacker, true));
        when(attacker.isSprinting()).thenReturn(false);
        tracker.preAttack(new PrePlayerAttackEntityEvent(attacker, victim, true));
        assertTrue(tracker.context(victim, attacker, "ENTITY_ATTACK", false).sprinting());
    }
    @Test public void cancelledSprintActionsDoNotChangeClassicState() {
        classic(false);
        var start = new PlayerToggleSprintEvent(attacker, true); start.setCancelled(true);
        tracker.toggleSprint(start);
        assertFalse(tracker.source(attacker, victim.getUniqueId()).sprint());
        tracker.toggleSprint(new PlayerToggleSprintEvent(attacker, true));
        var stop = new PlayerToggleSprintEvent(attacker, false); stop.setCancelled(true);
        tracker.toggleSprint(stop);
        assertTrue(tracker.source(attacker, victim.getUniqueId()).sprint());
    }
    @Test public void modernAndEnabledStopSprintUseServerState() {
        tracker.toggleSprint(new PlayerToggleSprintEvent(attacker, true));
        assertFalse(tracker.source(attacker, victim.getUniqueId()).sprint());
        classic(true);
        assertFalse(tracker.source(attacker, victim.getUniqueId()).sprint());
        when(attacker.isSprinting()).thenReturn(true);
        tracker.preAttack(new PrePlayerAttackEntityEvent(attacker, victim, true));
        when(attacker.isSprinting()).thenReturn(false);
        assertTrue(tracker.source(attacker, victim.getUniqueId()).sprint()); // Same-hit pre-reset snapshot.
        when(attacker.getTicksLived()).thenReturn(20);
        tracker.preAttack(new PrePlayerAttackEntityEvent(attacker, victim, true));
        assertFalse(tracker.source(attacker, victim.getUniqueId()).sprint());
    }
    @Test public void reloadAndModeSwitchPreserveHeldClientActionOnlyForClassic() {
        tracker.toggleSprint(new PlayerToggleSprintEvent(attacker, true));
        tracker.clear(); classic(false);
        assertTrue(tracker.source(attacker, victim.getUniqueId()).sprint());
        settings = settings.withMode(CombatMode.CLASSIC, ClassicSettings.defaults()); tracker.clear();
        assertTrue(tracker.source(attacker, victim.getUniqueId()).sprint());
        settings = settings.withMode(CombatMode.MODERN, ClassicSettings.defaults()); tracker.clear();
        assertFalse(tracker.source(attacker, victim.getUniqueId()).sprint());
        classic(false); tracker.clear();
        assertTrue(tracker.source(attacker, victim.getUniqueId()).sprint());
    }
    @Test public void quitRespawnAndPluginShutdownDiscardClientSprintState() {
        classic(false);
        tracker.toggleSprint(new PlayerToggleSprintEvent(attacker, true));
        var quit = mock(PlayerQuitEvent.class); when(quit.getPlayer()).thenReturn(attacker);
        tracker.quit(quit);
        assertFalse(tracker.source(attacker, victim.getUniqueId()).sprint());
        tracker.toggleSprint(new PlayerToggleSprintEvent(attacker, true));
        var respawn = mock(PlayerRespawnEvent.class); when(respawn.getPlayer()).thenReturn(attacker);
        tracker.respawn(respawn);
        assertFalse(tracker.source(attacker, victim.getUniqueId()).sprint());
        tracker.toggleSprint(new PlayerToggleSprintEvent(attacker, true));
        tracker.close();
        assertFalse(tracker.source(attacker, victim.getUniqueId()).sprint());
    }
    @Test public void joinReinitializesSprintFromTheNewSession() {
        classic(false);
        tracker.toggleSprint(new PlayerToggleSprintEvent(attacker, true));
        var join = mock(PlayerJoinEvent.class); when(join.getPlayer()).thenReturn(attacker);
        tracker.join(join);
        assertFalse(tracker.source(attacker, victim.getUniqueId()).sprint());
    }
    @Test public void classicPlayerShapedBotUsesItsCurrentEntitySprint() {
        classic(false);
        tracker.toggleSprint(new PlayerToggleSprintEvent(attacker, true));
        when(attacker.hasMetadata("NPC")).thenReturn(true);
        assertFalse(tracker.source(attacker, victim.getUniqueId()).sprint());
        when(attacker.isSprinting()).thenReturn(true);
        tracker.toggleSprint(new PlayerToggleSprintEvent(attacker, false));
        assertTrue(tracker.source(attacker, victim.getUniqueId()).sprint());
    }
    @Test public void foreignRegionSprintEventsNeverReadEntityState() {
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(attacker)).thenReturn(false);
        clearInvocations(attacker);
        tracker.toggleSprint(new PlayerToggleSprintEvent(attacker, true));
        var join = mock(PlayerJoinEvent.class); when(join.getPlayer()).thenReturn(attacker);
        tracker.join(join);
        verifyNoInteractions(attacker);
    }
    @Test public void preAttackRetainsChargeAndSprintBeforeNativeReset() {
        when(attacker.getAttackCooldown()).thenReturn(0.8f); when(attacker.isSprinting()).thenReturn(true);
        tracker.preAttack(new PrePlayerAttackEntityEvent(attacker, victim, true));
        when(attacker.isSprinting()).thenReturn(false); when(attacker.getAttackCooldown()).thenReturn(0f);
        var context = tracker.context(victim, attacker, "ENTITY_ATTACK", true);
        assertTrue(context.sprinting()); assertEquals(0.8, context.attackCharge(), 1e-6); assertTrue(context.syncOwnsVertical());
    }
    @Test public void snapshotIsNotReusedForDifferentTargetOrTick() {
        when(attacker.getAttackCooldown()).thenReturn(0.8f);
        tracker.preAttack(new PrePlayerAttackEntityEvent(attacker, victim, true));
        Player other = player(); assertTrue(Double.isNaN(tracker.context(other, attacker, "ENTITY_ATTACK", false).attackCharge()));
        when(attacker.getTicksLived()).thenReturn(11);
        assertTrue(Double.isNaN(tracker.context(victim, attacker, "ENTITY_ATTACK", false).attackCharge()));
    }
    @Test public void scopedSkillsNestAndRestoreEvenWhenActionThrows() {
        tracker.withType(attacker, victim, HitType.REGULARITY, () -> {
            assertEquals(HitType.REGULARITY, tracker.context(victim, attacker, "ENTITY_ATTACK", false).type());
            assertThrows(IllegalStateException.class, () -> tracker.withType(attacker, victim, HitType.GRIM_REAPER, () -> {
                assertEquals(HitType.GRIM_REAPER, tracker.context(victim, attacker, "ENTITY_ATTACK", false).type());
                throw new IllegalStateException("expected");
            }));
            assertEquals(HitType.REGULARITY, tracker.context(victim, attacker, "ENTITY_ATTACK", false).type());
        });
        assertEquals(HitType.MELEE, tracker.context(victim, attacker, "ENTITY_ATTACK", false).type());
    }
    @Test public void foreignRegionSourceIsNeverReadAndCannotBeMutated() {
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(attacker)).thenReturn(false); clearInvocations(attacker);
        var context = tracker.context(victim, attacker, "ENTITY_ATTACK", true);
        assertEquals(CombatantKind.UNKNOWN, context.attackerKind()); assertFalse(context.syncOwnsVertical());
        verifyNoInteractions(attacker);
        assertThrows(IllegalStateException.class, () -> tracker.setBot(attacker, true));
        assertThrows(IllegalStateException.class, () -> tracker.withType(attacker, victim, HitType.REGULARITY, () -> fail("must not run")));
    }
    @Test public void botsAreSeparateAndDoNotUsePlayerSyncPolicy() {
        when(attacker.hasMetadata("NPC")).thenReturn(true);
        var context = tracker.context(victim, attacker, "ENTITY_ATTACK", true);
        assertEquals(CombatantKind.BOT, context.attackerKind()); assertFalse(context.syncOwnsVertical());
    }
    @Test public void modernWeaponsAndCausesAreClassified() {
        assertEquals(HitType.MACE, HitClassifier.weapon("MACE")); assertEquals(HitType.SPEAR, HitClassifier.weapon("NETHERITE_SPEAR"));
        assertEquals(HitType.MELEE, HitClassifier.weapon("DIAMOND_AXE"));
        assertEquals(HitType.SWEEP, HitClassifier.cause("SWEEP_ATTACK", HitType.MELEE));
        assertEquals(HitType.SHIELD_BLOCK, HitClassifier.cause("SHIELD_BLOCK", HitType.MELEE));
        assertEquals(HitType.CRYSTAL, HitClassifier.cause("EXPLOSION", HitType.CRYSTAL));
        assertEquals(HitType.EXPLOSION, HitClassifier.cause("EXPLOSION", HitType.MELEE));
        assertEquals(HitType.DAMAGE, HitClassifier.cause("DAMAGE", HitType.UNKNOWN));
    }
    @Test public void projectilesDoNotBecomeMeleeBecauseShooterIsPlayer() {
        AbstractArrow arrow = mock(AbstractArrow.class);
        assertEquals(HitType.ARROW, HitClassifier.source(arrow));
        when(arrow.isShotFromCrossbow()).thenReturn(true); assertEquals(HitType.CROSSBOW, HitClassifier.source(arrow));
        assertEquals(HitType.TRIDENT, HitClassifier.source(mock(Trident.class)));
        assertEquals(HitType.FISHING_ROD, HitClassifier.source(mock(FishHook.class)));
    }
    @Test public void scopedNativeKnockbackHasSkillAndAttackerEvenWithoutNativeSource() {
        tracker.withType(attacker, victim, HitType.FISHING_ROD, () -> {
            var context = tracker.context(victim, null, "UNKNOWN", true);
            assertEquals(HitType.FISHING_ROD, context.type());
            assertEquals(attacker.getUniqueId(), context.attacker());
            assertFalse(context.syncOwnsVertical());
        });
        assertEquals(HitType.UNKNOWN, tracker.context(victim, null, "UNKNOWN", true).type());
    }
    @Test public void scopedRodKeepsActualHookAsDirectSourceAndPlayerAsAttacker() {
        FishHook hook = mock(FishHook.class);
        when(hook.getUniqueId()).thenReturn(UUID.randomUUID()); when(hook.getShooter()).thenReturn(attacker);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(hook)).thenReturn(true);
        tracker.withSource(attacker, victim, hook, HitType.FISHING_ROD, () -> {
            var context = tracker.context(victim, null, "UNKNOWN", false);
            assertEquals(HitType.FISHING_ROD, context.type());
            assertEquals(hook.getUniqueId(), context.source());
            assertEquals(attacker.getUniqueId(), context.attacker());
        });
        assertEquals(HitType.UNKNOWN, tracker.context(victim, null, "UNKNOWN", false).type());
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(hook)).thenReturn(false); clearInvocations(hook);
        assertThrows(IllegalStateException.class, () -> tracker.withSource(attacker, victim, hook, HitType.FISHING_ROD, () -> fail("foreign source")));
        verifyNoInteractions(hook);
    }
    @Test public void explosionCauseUsesModernPaperCauseWithoutLegacyEventHandling() {
        assertEquals(HitType.EXPLOSION, tracker.context(victim, null, "EXPLOSION", false).type());
        for (var method : CombatTracker.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(org.bukkit.event.EventHandler.class))
                assertFalse(method.getParameterTypes()[0].getName().equals("org.bukkit.event.entity.EntityKnockbackEvent"));
        }
    }
}

package cn.mx.modernkb;

import cn.mx.modernkb.api.*;
import cn.mx.modernkb.combat.CombatTracker;
import cn.mx.modernkb.combat.ClassicCombat;
import cn.mx.modernkb.config.*;
import cn.mx.modernkb.compat.RodCollision;
import cn.mx.modernkb.rod.ClassicRodContact;
import cn.mx.modernkb.rod.RodListener;
import io.papermc.paper.threadedregions.scheduler.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class RodListenerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private MockedStatic<Bukkit> bukkit;
    private Plugin plugin;
    private Player player, victim;
    private FishHook hook;
    private CombatTracker tracker;
    private PlayerStore store;
    private Settings settings;
    private RodListener listener;
    private EntityScheduler scheduler;
    @Before public void setup() {
        bukkit = mockStatic(Bukkit.class);
        plugin = mock(Plugin.class); when(plugin.isEnabled()).thenReturn(true);
        player = mock(Player.class); victim = mock(Player.class); hook = mock(FishHook.class);
        HookTestState.install(hook);
        for (Entity entity : List.of(player, victim, hook)) bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(entity)).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(victim.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getTicksLived()).thenReturn(100); when(hook.getShooter()).thenReturn(player);
        when(hook.getVelocity()).thenReturn(new Vector(1, 0.5, -1)); when(hook.isValid()).thenReturn(true);
        when(victim.isValid()).thenReturn(true);
        World world = mock(World.class);
        when(hook.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(hook.getWorld()).thenReturn(world); when(victim.getWorld()).thenReturn(world);
        var attached = new AtomicReference<Entity>();
        when(hook.getHookedEntity()).thenAnswer(call -> attached.get());
        doAnswer(call -> { attached.set(call.getArgument(0)); return null; }).when(hook).setHookedEntity(any());
        scheduler = mock(EntityScheduler.class); when(hook.getScheduler()).thenReturn(scheduler);
        tracker = mock(CombatTracker.class); when(tracker.kind(victim)).thenReturn(CombatantKind.PLAYER);
        store = new PlayerStore(temporary.getRoot().toPath().resolve("players.yml"));
        settings = new Settings(true, false, true, true, Set.of(), Map.of("default", KnockbackMathTest.profile(
                "rod.cast-interval-ticks", 4, "rod.recast-delay-ticks", 2, "rod.impact-strength", 0.2,
                "rod.horizontal-speed-multiplier", 1.1, "rod.max-lifetime-ticks", 40)), Map.of(), Map.of());
        listener = new RodListener(plugin, () -> settings, store, tracker);
    }
    @After public void close() { listener.close(); bukkit.close(); }
    private PlayerFishEvent cast() { return new PlayerFishEvent(player, null, hook, PlayerFishEvent.State.FISHING); }
    @Test public void successfulCastConsumesIntervalButFailedBiteDoesNotResetReel() {
        listener.accepted(cast()); when(player.getTicksLived()).thenReturn(103);
        var early = cast(); listener.limit(early); assertTrue(early.isCancelled());
        listener.accepted(new PlayerFishEvent(player, null, hook, PlayerFishEvent.State.FAILED_ATTEMPT));
        when(player.getTicksLived()).thenReturn(104); var onTime = cast(); listener.limit(onTime); assertFalse(onTime.isCancelled());
    }
    @Test public void acceptedReelAddsItsOwnDelay() {
        listener.accepted(new PlayerFishEvent(player, null, hook, PlayerFishEvent.State.REEL_IN));
        when(player.getTicksLived()).thenReturn(101); var early = cast(); listener.limit(early); assertTrue(early.isCancelled());
        when(player.getTicksLived()).thenReturn(102); var onTime = cast(); listener.limit(onTime); assertFalse(onTime.isCancelled());
    }
    @Test public void cancelledCastCannotMutateHookOnScheduledTick() {
        var event = cast(); listener.accepted(event); event.setCancelled(true);
        clearInvocations(hook); // Launch was already tuned before a later listener cancelled insertion.
        var captured = org.mockito.ArgumentCaptor.forClass(Consumer.class);
        verify(scheduler).run(eq(plugin), captured.capture(), isNull());
        captured.getValue().accept(mock(ScheduledTask.class)); verify(hook, never()).setVelocity(any());
    }
    @Test public void scalesNewUnspawnedHookImmediatelyOnlyOnceAndStopsLifetimeTask() {
        ScheduledTask monitor = mock(ScheduledTask.class);
        when(scheduler.runAtFixedRate(eq(plugin), any(), any(), eq(1L), eq(1L))).thenReturn(monitor);
        when(hook.isValid()).thenReturn(false); listener.accepted(cast());
        verify(hook).setVelocity(new Vector(1.1, 0.5, -1.1));
        when(hook.isValid()).thenReturn(true);
        var first = org.mockito.ArgumentCaptor.forClass(Consumer.class);
        verify(scheduler).run(eq(plugin), first.capture(), isNull()); first.getValue().accept(mock(ScheduledTask.class));
        verify(hook, times(1)).setVelocity(new Vector(1.1, 0.5, -1.1));
        var repeating = org.mockito.ArgumentCaptor.forClass(Consumer.class);
        verify(scheduler).runAtFixedRate(eq(plugin), repeating.capture(), any(), eq(1L), eq(1L));
        when(hook.getTicksLived()).thenReturn(40); repeating.getValue().accept(monitor);
        verify(hook).remove(); verify(monitor).cancel();
    }
    @Test public void collisionUsesNativeKnockbackAndSkillScopeWithoutAddingDamage() {
        when(victim.getVelocity()).thenReturn(new Vector(), new Vector(0.2, 0.2, 0));
        doAnswer(call -> { ((Runnable)call.getArgument(3)).run(); return null; }).when(tracker).withType(eq(player), eq(victim), eq(HitType.FISHING_ROD), any());
        listener.impact(new ProjectileHitEvent(hook, victim));
        verify(victim).knockback(0.2, -1, 1); verify(victim).setVelocity(new Vector(0.2, 0.2, 0));
        verify(victim, never()).damage(anyDouble());
    }
    @Test public void cancelledOrUnchangedRodKnockbackDoesNotMarkVelocityDirty() {
        when(victim.getVelocity()).thenReturn(new Vector());
        doAnswer(call -> { ((Runnable)call.getArgument(3)).run(); return null; }).when(tracker).withType(eq(player), eq(victim), eq(HitType.FISHING_ROD), any());
        listener.impact(new ProjectileHitEvent(hook, victim));
        verify(victim, never()).setVelocity(any());
    }
    @Test public void modeChangeBeforeFirstHookTickDoesNotApplyOldFlightSettings() {
        listener.accepted(cast());
        clearInvocations(hook);
        var callback = org.mockito.ArgumentCaptor.forClass(Consumer.class);
        verify(scheduler).run(eq(plugin), callback.capture(), isNull());
        settings = settings.withMode(CombatMode.CLASSIC, ClassicSettings.defaults());
        callback.getValue().accept(mock(ScheduledTask.class));
        verify(hook, never()).setVelocity(any());
    }
    @Test public void hookWithoutInitialRegionOwnershipDefersFlightTuningToOwnedTick() {
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(hook)).thenReturn(false);
        listener.accepted(cast()); verify(hook, never()).getVelocity(); verify(hook, never()).setVelocity(any());
        var first = org.mockito.ArgumentCaptor.forClass(Consumer.class);
        verify(scheduler).run(eq(plugin), first.capture(), isNull());
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(hook)).thenReturn(true);
        first.getValue().accept(mock(ScheduledTask.class));
        verify(hook).setVelocity(new Vector(1.1, 0.5, -1.1));
    }
    @Test public void disabledCollisionAndForeignRegionNeverApplyNativeKnockback() {
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(victim)).thenReturn(false);
        listener.impact(new ProjectileHitEvent(hook, victim)); verifyNoInteractions(tracker);
    }
    @Test public void classicCollisionUsesDamagePipelineAndOwnsNativeAttachment() {
        settings = settings.withMode(CombatMode.CLASSIC, ClassicSettings.defaults());
        ClassicCombat classic = mock(ClassicCombat.class);
        listener = new RodListener(plugin, () -> settings, store, tracker, classic);
        when(classic.rodImpactResult(hook, player, victim, 0.2)).thenAnswer(call -> {
            assertTrue(ClassicRodContact.claim(hook));
            assertTrue(ClassicRodContact.attach(hook, victim, plugin));
            return ClassicCombat.RodResult.APPLIED;
        });
        when(victim.getVelocity()).thenReturn(new Vector(), new Vector(0.2, 0.18, 0));
        var event = new ProjectileHitEvent(hook, victim); listener.impact(event);
        assertTrue(event.isCancelled());
        verify(classic).rodImpactResult(hook, player, victim, 0.2);
        verify(victim, never()).knockback(anyDouble(), anyDouble(), anyDouble());
        verify(victim, never()).setVelocity(any());
        verify(victim).hideEntity(plugin, hook); verify(player, never()).hideEntity(any(), any());
        var order = inOrder(victim, hook);
        order.verify(victim).hideEntity(plugin, hook);
        order.verify(hook).setHookedEntity(victim);
        verify(hook, never()).setHookedEntity(null); verify(hook, never()).remove();
        verify(hook).setVelocity(new Vector());
        assertSame(victim, hook.getHookedEntity());
    }
    @Test public void classicDoesNotRequeueVelocityAfterCombatHasAlreadyDeliveredIt() {
        settings = settings.withMode(CombatMode.CLASSIC, ClassicSettings.defaults());
        ClassicCombat classic = mock(ClassicCombat.class);
        listener = new RodListener(plugin, () -> settings, store, tracker, classic);
        when(victim.getVelocity()).thenReturn(new Vector(0.5, 0.34, 0));
        when(classic.rodImpactResult(hook, player, victim, 0.2)).thenReturn(ClassicCombat.RodResult.APPLIED);
        listener.impact(new ProjectileHitEvent(hook, victim));
        verify(victim, never()).setVelocity(any());
    }
    @Test public void retainedRejectedHookDoesNotRepeatDamageOrDropOnLaterNativeContacts() {
        settings = settings.withMode(CombatMode.CLASSIC, ClassicSettings.defaults());
        ClassicCombat classic = mock(ClassicCombat.class);
        listener = new RodListener(plugin, () -> settings, store, tracker, classic);
        var first = new ProjectileHitEvent(hook, victim); listener.impact(first);
        var second = new ProjectileHitEvent(hook, victim); listener.impact(second);
        // A reconstructed listener must still recognize the same spent bobber.
        listener = new RodListener(plugin, () -> settings, store, tracker, classic);
        var afterReload = new ProjectileHitEvent(hook, victim); listener.impact(afterReload);
        assertTrue(first.isCancelled()); assertTrue(second.isCancelled()); assertTrue(afterReload.isCancelled());
        verify(classic, times(1)).rodImpactResult(hook, player, victim, 0.2);
        verify(hook, times(1)).setVelocity(new Vector(0, -0.05, 0));
        verify(hook, never()).remove();
    }
    @Test public void attachedHookKeepsFollowingOneVictimAcrossRepeatContactsAndListenerReconstruction() {
        settings = settings.withMode(CombatMode.CLASSIC, ClassicSettings.defaults());
        ClassicCombat classic = mock(ClassicCombat.class);
        when(classic.rodImpactResult(hook, player, victim, 0.2)).thenAnswer(call -> {
            assertTrue(ClassicRodContact.claim(hook));
            assertTrue(ClassicRodContact.attach(hook, victim, plugin));
            return ClassicCombat.RodResult.APPLIED;
        });
        listener = new RodListener(plugin, () -> settings, store, tracker, classic);
        listener.impact(new ProjectileHitEvent(hook, victim));
        listener.impact(new ProjectileHitEvent(hook, player));
        listener = new RodListener(plugin, () -> settings, store, tracker, classic);
        listener.impact(new ProjectileHitEvent(hook, victim));
        verify(classic, times(1)).rodImpactResult(hook, player, victim, 0.2);
        verify(victim, times(1)).hideEntity(plugin, hook);
        verify(hook, times(1)).setHookedEntity(victim);
        verify(hook, never()).setHookedEntity(null);
        verify(hook, times(1)).setVelocity(new Vector());
        verify(hook, never()).setVelocity(new Vector(0, -0.05, 0));
        assertSame(victim, hook.getHookedEntity());
    }
    @Test public void successfulDeliveryCanAttachAfterContactCleanupAndFinishNeverMovesItAgain() {
        assertTrue(ClassicRodContact.claim(hook));
        ClassicRodContact.finish(hook);
        assertTrue(ClassicRodContact.attach(hook, victim, plugin));
        clearInvocations(hook, victim);
        ClassicRodContact.finish(hook);
        assertTrue(ClassicRodContact.attach(hook, victim, plugin));
        verify(victim, never()).hideEntity(any(), any());
        verify(hook, never()).setHookedEntity(any());
        verify(hook, never()).setVelocity(any());
        assertSame(victim, hook.getHookedEntity());
        assertFalse(ClassicRodContact.claim(hook));
    }
    @Test public void freshForeignInvalidOrDifferentWorldTargetsCannotBeAttached() {
        assertFalse(ClassicRodContact.attach(hook, victim, plugin)); // No accepted contact claim.
        assertTrue(ClassicRodContact.claim(hook));
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(victim)).thenReturn(false);
        clearInvocations(victim);
        assertFalse(ClassicRodContact.attach(hook, victim, plugin));
        verifyNoInteractions(victim);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(victim)).thenReturn(true);
        when(victim.isValid()).thenReturn(false);
        assertFalse(ClassicRodContact.attach(hook, victim, plugin));
        when(victim.isValid()).thenReturn(true);
        when(victim.getWorld()).thenReturn(mock(World.class));
        assertFalse(ClassicRodContact.attach(hook, victim, plugin));
        verify(victim, never()).hideEntity(any(), any());
        verify(hook, never()).setHookedEntity(any());
    }
    @Test public void attachmentFailureRestoresOnlyThisPluginsVisibilityAndLeavesContactSpent() {
        assertTrue(ClassicRodContact.claim(hook));
        var failure = new IllegalStateException("native attachment failed");
        doThrow(failure).when(hook).setHookedEntity(victim);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> ClassicRodContact.attach(hook, victim, plugin)));
        verify(victim).hideEntity(plugin, hook); verify(victim).showEntity(plugin, hook);
        verify(hook).setHookedEntity(null);
        assertTrue(ClassicRodContact.spent(hook)); assertFalse(ClassicRodContact.claim(hook));
        ClassicRodContact.finish(hook);
        verify(hook).setVelocity(new Vector(0, -0.05, 0));
    }
    @Test public void removalDuringHideCannotAttachADeadHook() {
        assertTrue(ClassicRodContact.claim(hook));
        doAnswer(call -> { when(hook.isValid()).thenReturn(false); return null; })
                .when(victim).hideEntity(plugin, hook);
        assertFalse(ClassicRodContact.attach(hook, victim, plugin));
        verify(victim).showEntity(plugin, hook);
        verify(hook, never()).setHookedEntity(any());
        verify(hook, never()).setVelocity(any());
    }
    @Test public void modeChangeOrNewerDamageDuringHideRollsBackWithoutAttaching() {
        assertTrue(ClassicRodContact.claim(hook));
        var current = new AtomicBoolean(true);
        doAnswer(call -> { current.set(false); return null; }).when(victim).hideEntity(plugin, hook);
        assertFalse(ClassicRodContact.attach(hook, victim, plugin, current::get));
        verify(victim).hideEntity(plugin, hook); verify(victim).showEntity(plugin, hook);
        verify(hook, never()).setHookedEntity(any()); verify(hook, never()).setVelocity(any());
        assertFalse(ClassicRodContact.claim(hook));
    }
    @Test public void nonPlayerAttachmentUsesNativeHookWithoutAnyViewerHiding() {
        LivingEntity mob = mock(LivingEntity.class);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(mob)).thenReturn(true);
        var world = hook.getWorld();
        when(mob.isValid()).thenReturn(true); when(mob.getWorld()).thenReturn(world);
        assertTrue(ClassicRodContact.claim(hook));
        assertTrue(ClassicRodContact.attach(hook, mob, plugin));
        verify(hook).setHookedEntity(mob); verify(hook).setVelocity(new Vector());
        verify(victim, never()).hideEntity(any(), any()); verify(player, never()).hideEntity(any(), any());
    }
    @Test public void impactDoesNotConsumeRecastDelayBeforeThePlayerActuallyReels() {
        settings = settings.withMode(CombatMode.CLASSIC, ClassicSettings.defaults());
        listener.accepted(cast());
        when(player.getTicksLived()).thenReturn(104);
        listener.impact(new ProjectileHitEvent(hook, victim));
        var beforeReel = cast(); listener.limit(beforeReel); assertFalse(beforeReel.isCancelled());
        listener.accepted(new PlayerFishEvent(player, null, hook, PlayerFishEvent.State.REEL_IN));
        when(player.getTicksLived()).thenReturn(105);
        var early = cast(); listener.limit(early); assertTrue(early.isCancelled());
        when(player.getTicksLived()).thenReturn(106);
        var ready = cast(); listener.limit(ready); assertFalse(ready.isCancelled());
        verify(hook, never()).remove();
    }
    @Test public void rejectedClassicRodCannotForceAVelocityNotification() {
        settings = settings.withMode(CombatMode.CLASSIC, ClassicSettings.defaults());
        ClassicCombat classic = mock(ClassicCombat.class);
        listener = new RodListener(plugin, () -> settings, store, tracker, classic);
        when(victim.getVelocity()).thenReturn(new Vector(), new Vector(0.5, 0.34, 0));
        when(classic.rodImpactResult(hook, player, victim, 0.2)).thenReturn(ClassicCombat.RodResult.REJECTED);
        listener.impact(new ProjectileHitEvent(hook, victim)); verify(victim, never()).setVelocity(any());
        verify(victim, never()).hideEntity(any(), any()); verify(hook, never()).setHookedEntity(victim);
        verify(hook).setVelocity(new Vector(0, -0.05, 0));
    }
    @Test public void alreadyCancelledProjectileNeverStartsClassicDamage() {
        settings = settings.withMode(CombatMode.CLASSIC, ClassicSettings.defaults());
        ClassicCombat classic = mock(ClassicCombat.class);
        listener = new RodListener(plugin, () -> settings, store, tracker, classic);
        var event = new ProjectileHitEvent(hook, victim); event.setCancelled(true); listener.impact(event);
        verifyNoInteractions(classic); verifyNoInteractions(tracker);
        verify(victim, never()).hideEntity(any(), any()); verify(hook, never()).setHookedEntity(victim);
    }
    @Test public void classicReelCancelsNativePullDetachesHookAndConsumesRecastDelay() {
        settings = settings.withMode(CombatMode.CLASSIC, ClassicSettings.defaults());
        var reel = new PlayerFishEvent(player, victim, hook, PlayerFishEvent.State.CAUGHT_ENTITY);
        listener.preventClassicPull(reel); listener.accepted(reel);
        assertTrue(reel.isCancelled()); verify(hook).setHookedEntity(null); verify(hook).remove();
        verify(hook, never()).pullHookedEntity(); verifyNoInteractions(victim);
        when(player.getTicksLived()).thenReturn(101); var early = cast(); listener.limit(early); assertTrue(early.isCancelled());
        when(player.getTicksLived()).thenReturn(102); var onTime = cast(); listener.limit(onTime); assertFalse(onTime.isCancelled());
    }
    @Test public void classicReelOfForeignRegionVictimOnlyTouchesOwnedHook() {
        settings = settings.withMode(CombatMode.CLASSIC, ClassicSettings.defaults());
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(victim)).thenReturn(false); clearInvocations(victim);
        var reel = new PlayerFishEvent(player, victim, hook, PlayerFishEvent.State.CAUGHT_ENTITY);
        listener.preventClassicPull(reel);
        assertTrue(reel.isCancelled()); verify(hook).remove(); verifyNoInteractions(victim);
    }
    @Test public void modernReelsAndClassicItemFishingKeepNativeBehaviour() {
        var modern = new PlayerFishEvent(player, victim, hook, PlayerFishEvent.State.CAUGHT_ENTITY);
        listener.preventClassicPull(modern); assertFalse(modern.isCancelled());
        settings = settings.withMode(CombatMode.CLASSIC, ClassicSettings.defaults());
        var item = new PlayerFishEvent(player, mock(Item.class), hook, PlayerFishEvent.State.CAUGHT_ENTITY);
        listener.preventClassicPull(item); assertFalse(item.isCancelled());
        verify(hook, never()).remove(); verify(hook, never()).setHookedEntity(any());
    }
    @Test public void excludedAnglerAndForeignHookAreNotMutatedOnReel() {
        settings = settings.withMode(CombatMode.CLASSIC, ClassicSettings.defaults());
        store.excluded(player.getUniqueId(), true);
        var excluded = new PlayerFishEvent(player, victim, hook, PlayerFishEvent.State.CAUGHT_ENTITY);
        listener.preventClassicPull(excluded); assertFalse(excluded.isCancelled());
        store.excluded(player.getUniqueId(), false);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(hook)).thenReturn(false); clearInvocations(hook);
        var foreign = new PlayerFishEvent(player, victim, hook, PlayerFishEvent.State.CAUGHT_ENTITY);
        listener.preventClassicPull(foreign); assertFalse(foreign.isCancelled()); verifyNoInteractions(hook);
    }
    @Test public void spentClassicHookCannotPullDuringModeOrExclusionCleanup() {
        assertTrue(ClassicRodContact.claim(hook));
        assertTrue(ClassicRodContact.attach(hook, victim, plugin));
        settings = settings.withMode(CombatMode.MODERN, ClassicSettings.defaults());
        store.excluded(player.getUniqueId(), true);
        var reel = new PlayerFishEvent(player, victim, hook, PlayerFishEvent.State.CAUGHT_ENTITY);
        listener.preventClassicPull(reel);
        assertTrue(reel.isCancelled());
        verify(hook).setHookedEntity(null); verify(hook).remove();
        verify(hook, never()).pullHookedEntity();
        verify(victim, never()).showEntity(any(), any()); // Core removal clears the visibility entry.
    }
    @Test public void resetRemovesOwnedAttachmentsWithoutShowingTheHookAgain() {
        listener.accepted(cast());
        assertTrue(ClassicRodContact.claim(hook));
        assertTrue(ClassicRodContact.attach(hook, victim, plugin));
        clearInvocations(hook, victim, scheduler);
        listener.reset();
        verify(hook).setHookedEntity(null); verify(hook).remove();
        verify(victim, never()).showEntity(any(), any());
        verifyNoInteractions(scheduler);
    }
    @Test public void resetDefersForeignHookRemovalToItsOwningRegion() {
        listener.accepted(cast());
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(hook)).thenReturn(false);
        clearInvocations(hook, scheduler);
        listener.reset();
        verify(hook, never()).isValid(); verify(hook, never()).remove();
        var deferred = org.mockito.ArgumentCaptor.forClass(Consumer.class);
        verify(scheduler).run(eq(plugin), deferred.capture(), isNull());
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(hook)).thenReturn(true);
        deferred.getValue().accept(mock(ScheduledTask.class));
        verify(hook).setHookedEntity(null); verify(hook).remove();
    }
    @Test public void normalDisableRemovesOwnedHooksWithoutSchedulingDisabledPluginTasks() {
        listener.accepted(cast());
        assertTrue(ClassicRodContact.claim(hook));
        assertTrue(ClassicRodContact.attach(hook, victim, plugin));
        when(plugin.isEnabled()).thenReturn(false);
        clearInvocations(hook, victim, scheduler);
        listener.close();
        verify(hook).setHookedEntity(null); verify(hook).remove();
        verify(victim, never()).showEntity(any(), any()); verifyNoInteractions(scheduler);
    }
    @Test public void disableNeverReadsOrMutatesAForeignHook() {
        listener.accepted(cast());
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(hook)).thenReturn(false);
        when(plugin.isEnabled()).thenReturn(false);
        clearInvocations(hook, scheduler);
        listener.close();
        verifyNoInteractions(hook, scheduler);
    }
    @Test public void victimQuitRetiresItsAttachment() {
        listener.accepted(cast());
        assertTrue(ClassicRodContact.claim(hook));
        assertTrue(ClassicRodContact.attach(hook, victim, plugin));
        var quit = mock(PlayerQuitEvent.class); when(quit.getPlayer()).thenReturn(victim);
        listener.quit(quit);
        verify(hook).setHookedEntity(null); verify(hook).remove();
        verify(victim, never()).showEntity(any(), any());
    }
    @Test public void anglerRespawnRetiresItsOldHook() {
        listener.accepted(cast());
        var respawn = mock(PlayerRespawnEvent.class); when(respawn.getPlayer()).thenReturn(player);
        listener.respawn(respawn);
        verify(hook).setHookedEntity(null); verify(hook).remove();
    }
    private Consumer<ScheduledTask> firstTick() {
        var callback = org.mockito.ArgumentCaptor.forClass(Consumer.class);
        verify(scheduler).run(eq(plugin), callback.capture(), isNull());
        return callback.getValue();
    }
    private RodCollision sweptRod() {
        settings = settings.withMode(CombatMode.CLASSIC, ClassicSettings.defaults());
        RodCollision collision = mock(RodCollision.class);
        listener = new RodListener(plugin, () -> settings, store, tracker, mock(ClassicCombat.class), collision);
        when(hook.getState()).thenReturn(FishHook.HookState.UNHOOKED);
        return collision;
    }
    @Test public void classicSweepRunsOnFirstOwnedTickAndDispatchesOneProtectedContact() {
        var collision = sweptRod(); when(collision.trace(hook)).thenReturn(victim);
        var manager = mock(org.bukkit.plugin.PluginManager.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
        doAnswer(call -> {
            var hit = (ProjectileHitEvent)call.getArgument(0);
            assertSame(victim, hit.getHitEntity()); assertSame(hook, hit.getEntity());
            hit.setCancelled(true); listener.impact(hit);
            return null;
        }).when(manager).callEvent(any());
        listener.accepted(cast()); firstTick().accept(mock(ScheduledTask.class));
        verify(collision).trace(hook); verify(manager).callEvent(any(ProjectileHitEvent.class));
        verify(hook, never()).remove(); verifyNoInteractions(tracker);
        var repeat = org.mockito.ArgumentCaptor.forClass(Consumer.class);
        verify(scheduler).runAtFixedRate(eq(plugin), repeat.capture(), any(), eq(1L), eq(1L));
        repeat.getValue().accept(mock(ScheduledTask.class));
        verifyNoMoreInteractions(collision); // Retained spent hooks still get lifetime checks, not new contacts.
    }
    @Test public void classicSweepContinuesWithUnlimitedFlightSettingsAndEndsOnRemoval() {
        settings = new Settings(true, false, true, true, Set.of(), Map.of("default", KnockbackMathTest.profile(
                "rod.impact-strength", 0.4, "rod.horizontal-speed-multiplier", 1, "rod.vertical-speed-multiplier", 1,
                "rod.max-distance", 0, "rod.max-lifetime-ticks", 0)), Map.of(), Map.of());
        var collision = sweptRod();
        listener.accepted(cast()); firstTick().accept(mock(ScheduledTask.class));
        var repeat = org.mockito.ArgumentCaptor.forClass(Consumer.class);
        verify(scheduler).runAtFixedRate(eq(plugin), repeat.capture(), any(), eq(1L), eq(1L));
        var task = mock(ScheduledTask.class);
        repeat.getValue().accept(task); verify(collision, times(2)).trace(hook);
        when(hook.isValid()).thenReturn(false); repeat.getValue().accept(task);
        verify(task).cancel(); verifyNoMoreInteractions(collision);
    }
    @Test public void hookedBobbingWaterAndGroundStatesDoNotAcquireNewTargets() {
        var collision = sweptRod(); listener.accepted(cast()); var tick = firstTick();
        for (var state : new FishHook.HookState[]{FishHook.HookState.HOOKED_ENTITY, FishHook.HookState.BOBBING}) {
            when(hook.getState()).thenReturn(state); tick.accept(mock(ScheduledTask.class));
        }
        when(hook.getState()).thenReturn(FishHook.HookState.UNHOOKED);
        when(hook.isInWater()).thenReturn(true); tick.accept(mock(ScheduledTask.class));
        when(hook.isInWater()).thenReturn(false); when(hook.isOnGround()).thenReturn(true);
        tick.accept(mock(ScheduledTask.class)); verifyNoInteractions(collision);
    }
    @Test public void sweepDoesNotDispatchForeignRegionContactOrOperateInModern() {
        var collision = sweptRod(); when(collision.trace(hook)).thenReturn(victim);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(victim)).thenReturn(false);
        listener.accepted(cast()); firstTick().accept(mock(ScheduledTask.class));
        verify(collision).trace(hook); verify(hook, never()).remove(); verifyNoInteractions(victim);
        settings = settings.withMode(CombatMode.MODERN, ClassicSettings.defaults());
        clearInvocations(scheduler, collision);
        listener.accepted(cast()); firstTick().accept(mock(ScheduledTask.class));
        verifyNoInteractions(collision);
    }
    @Test public void classicForeignRegionCollisionCannotAttachOrReadVictim() {
        settings = settings.withMode(CombatMode.CLASSIC, ClassicSettings.defaults());
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(victim)).thenReturn(false); clearInvocations(victim);
        var event = new ProjectileHitEvent(hook, victim); listener.impact(event);
        assertTrue(event.isCancelled()); verifyNoInteractions(victim); verifyNoInteractions(tracker);
    }
}

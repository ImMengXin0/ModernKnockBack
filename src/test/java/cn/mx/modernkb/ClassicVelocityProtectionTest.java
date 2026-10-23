package cn.mx.modernkb;

import cn.mx.modernkb.api.*;
import cn.mx.modernkb.combat.*;
import cn.mx.modernkb.config.*;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Models the entity motion separately from the event's sometimes pre-hit vector. */
public class ClassicVelocityProtectionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private MockedStatic<Bukkit> bukkit;
    private ClassicCombat classic;
    private CombatTracker tracker;
    private PlayerStore store;
    private Settings settings;
    private Player victim;
    private HitContext melee, rod;
    private AtomicInteger tick;
    private AtomicReference<Vector> motion;

    @Before public void setup() {
        bukkit = mockStatic(Bukkit.class);
        Plugin plugin = mock(Plugin.class);
        when(plugin.namespace()).thenReturn("modernknockback");
        when(plugin.getName()).thenReturn("ModernKnockback");
        victim = mock(Player.class);
        UUID victimId = UUID.randomUUID(), attackerId = UUID.randomUUID();
        when(victim.getUniqueId()).thenReturn(victimId);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(victim)).thenReturn(true);
        tick = new AtomicInteger(100);
        when(victim.getTicksLived()).thenAnswer(call -> tick.get());
        motion = new AtomicReference<>(new Vector());
        when(victim.getVelocity()).thenAnswer(call -> motion.get().clone());
        doAnswer(call -> { motion.set(((Vector) call.getArgument(0)).clone()); return null; })
                .when(victim).setVelocity(any());
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(victim.getScheduler()).thenReturn(scheduler);
        when(scheduler.runDelayed(any(), any(), any(), anyLong())).thenReturn(mock(ScheduledTask.class));
        settings = new Settings(true, false, true, true, Set.of(), Map.of(), Map.of(), Map.of())
                .withMode(CombatMode.CLASSIC, ClassicSettings.defaults());
        tracker = mock(CombatTracker.class);
        when(tracker.kind(victim)).thenReturn(CombatantKind.PLAYER);
        when(tracker.source(any(), eq(victimId))).thenReturn(new CombatTracker.Source(
                attackerId, attackerId, CombatantKind.PLAYER, HitType.MELEE, false, 1));
        store = new PlayerStore(temporary.getRoot().toPath().resolve("players.yml"));
        classic = new ClassicCombat(plugin, () -> settings, store, tracker);
        melee = new HitContext(victimId, attackerId, attackerId, HitType.MELEE,
                CombatantKind.PLAYER, CombatantKind.PLAYER, "ENTITY_ATTACK", false, false, false, 1, false);
        rod = new HitContext(victimId, attackerId, UUID.randomUUID(), HitType.FISHING_ROD,
                CombatantKind.PLAYER, CombatantKind.PLAYER, "UNKNOWN", false, false, false, 1, false);
    }

    @After public void close() { bukkit.close(); }

    private void pending(HitContext context) {
        classic.applied(victim, new Vector(0.4, 0.36, 0), settings, context);
    }

    private EntityKnockbackEvent prepared() {
        var knockback = new EntityKnockbackEvent(victim, EntityKnockbackEvent.Cause.ENTITY_ATTACK,
                new Vector(0.4, 0.36, 0));
        classic.prepare(knockback, melee, settings,
                new ClassicCombat.Calculation(knockback.getKnockback(), () -> {}));
        classic.finishedKnockback(knockback);
        return knockback;
    }

    private EntityDamageByEntityEvent acceptedDamage() {
        var damage = mock(EntityDamageByEntityEvent.class);
        when(damage.getEntity()).thenReturn(victim);
        when(damage.getDamager()).thenReturn(mock(Player.class));
        when(damage.getDamageSource()).thenReturn(mock(DamageSource.class));
        when(damage.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_ATTACK);
        when(damage.getFinalDamage()).thenReturn(3d);
        when(victim.getLastDamageCause()).thenReturn(damage);
        classic.acceptedDamage(damage);
        return damage;
    }

    @Test public void meleeProtectsActualDispatchMotionIncludingLaterStrafeAndJumpChanges() {
        pending(melee);
        Vector current = new Vector(0.31, 0.24, -0.18);
        motion.set(current);
        // Player.causeExtraKnockback passes pre-hit motion to PlayerVelocityEvent.
        var event = new PlayerVelocityEvent(victim, new Vector(0, 0.42, 0));
        classic.beginVelocity(event);
        motion.set(new Vector(0.31, 0.8, -0.18)); // KnockbackSync's direct entity write.
        classic.velocity(event);
        assertEquals(current, event.getVelocity());
        assertEquals(current, motion.get());
        verify(victim, times(1)).setVelocity(current);
    }

    @Test public void equalEventVectorStillRestoresDirectSyncMutationBeforeNativeSend() {
        pending(melee);
        Vector initial = new Vector(0.4, 0.36, 0);
        motion.set(initial);
        var event = new PlayerVelocityEvent(victim, initial.clone());
        classic.beginVelocity(event);
        motion.set(new Vector(0.4, 0.8, 0));
        classic.velocity(event);
        // ServerEntity and Player both skip event writeback when it equals the initial vector.
        if (!initial.equals(event.getVelocity())) victim.setVelocity(event.getVelocity());
        assertEquals(initial, event.getVelocity());
        assertEquals(initial, motion.get());
        verify(victim, times(1)).setVelocity(initial);
    }

    @Test public void unchangedEntityMotionDoesNotCauseAnExtraVelocityWrite() {
        pending(melee);
        Vector current = new Vector(0.4, 0.36, 0);
        motion.set(current);
        var event = new PlayerVelocityEvent(victim, new Vector());
        classic.beginVelocity(event);
        event.setVelocity(new Vector(0.4, 0.8, 0));
        classic.velocity(event);
        assertEquals(current, event.getVelocity());
        verify(victim, never()).setVelocity(any());
    }

    @Test public void laterCancelledKnockbackCannotProtectAnUnrelatedVelocityEvent() {
        var knockback = prepared();
        knockback.setCancelled(true); // A later MONITOR cancelled before native KB application.
        Vector current = new Vector(0.1, 0.2, 0.3);
        motion.set(current);
        var event = new PlayerVelocityEvent(victim, current);
        classic.beginVelocity(event);
        event.setVelocity(new Vector(0.1, 0.8, 0.3));
        classic.velocity(event);
        assertEquals(new Vector(0.1, 0.8, 0.3), event.getVelocity());
        verify(victim, never()).setVelocity(any());
    }

    @Test public void laterNativeKnockbackChangesArePreservedInsteadOfTheMonitorSnapshot() {
        var knockback = prepared();
        Vector finalMotion = new Vector(0.22, 0.28, -0.2);
        knockback.setKnockback(finalMotion); // A later listener changes the accepted native vector.
        motion.set(finalMotion); // Core applies the final event after all listeners.
        var event = new PlayerVelocityEvent(victim, new Vector());
        classic.beginVelocity(event);
        motion.set(new Vector(0.22, 0.8, -0.2));
        classic.velocity(event);
        assertEquals(finalMotion, event.getVelocity());
        assertEquals(finalMotion, motion.get());
    }

    @Test public void cancellationBetweenVelocityPrioritiesInvalidatesTheCapturedVector() {
        var knockback = prepared();
        motion.set(new Vector(0.4, 0.36, 0));
        var event = new PlayerVelocityEvent(victim, new Vector());
        classic.beginVelocity(event);
        knockback.setCancelled(true);
        event.setVelocity(new Vector(0, 0.8, 0));
        classic.velocity(event);
        assertEquals(new Vector(0, 0.8, 0), event.getVelocity());
        verify(victim, never()).setVelocity(any());
    }

    @Test public void damageCancellationBetweenPrioritiesDoesNotRestoreTheOldHit() {
        var damage = acceptedDamage();
        prepared();
        motion.set(new Vector(0.4, 0.36, 0));
        var event = new PlayerVelocityEvent(victim, new Vector());
        classic.beginVelocity(event);
        when(damage.isCancelled()).thenReturn(true);
        event.setVelocity(new Vector(0, 0.8, 0));
        classic.velocity(event);
        assertEquals(new Vector(0, 0.8, 0), event.getVelocity());
        verify(victim, never()).setVelocity(any());
    }

    @Test public void unrelatedDamageBetweenPrioritiesInvalidatesTheOldHitEvenWithoutNewKb() {
        acceptedDamage();
        prepared();
        motion.set(new Vector(0.4, 0.36, 0));
        var event = new PlayerVelocityEvent(victim, new Vector());
        classic.beginVelocity(event);
        when(victim.getLastDamageCause()).thenReturn(mock(EntityDamageEvent.class));
        event.setVelocity(new Vector(0, 0.8, 0));
        classic.velocity(event);
        assertEquals(new Vector(0, 0.8, 0), event.getVelocity());
        verify(victim, never()).setVelocity(any());
    }

    @Test public void cancelledVelocityDoesNotWriteAndConsumesOnlyItsOwnProtection() {
        pending(melee);
        motion.set(new Vector(0.4, 0.36, 0));
        var event = new PlayerVelocityEvent(victim, new Vector());
        classic.beginVelocity(event);
        event.setCancelled(true);
        classic.velocity(event);
        assertEquals(new Vector(), event.getVelocity());
        verify(victim, never()).setVelocity(any());
        var later = new PlayerVelocityEvent(victim, new Vector(0, 0.1, 0));
        classic.beginVelocity(later);
        classic.velocity(later);
        assertEquals(new Vector(0, 0.1, 0), later.getVelocity());
    }

    @Test public void modeSwitchBetweenPrioritiesDoesNotWriteClassicMotion() {
        pending(melee);
        motion.set(new Vector(0.4, 0.36, 0));
        var event = new PlayerVelocityEvent(victim, new Vector());
        classic.beginVelocity(event);
        settings = settings.withMode(CombatMode.MODERN, ClassicSettings.defaults());
        event.setVelocity(new Vector(0, 0.8, 0));
        classic.velocity(event);
        assertEquals(new Vector(0, 0.8, 0), event.getVelocity());
        verify(victim, never()).setVelocity(any());
    }

    @Test public void exclusionBeforeDispatchLeavesTheVelocityUntouched() {
        pending(melee);
        store.excluded(victim.getUniqueId(), true);
        motion.set(new Vector(0.4, 0.36, 0));
        var event = new PlayerVelocityEvent(victim, new Vector(0, 0.8, 0));
        classic.beginVelocity(event);
        classic.velocity(event);
        assertEquals(new Vector(0, 0.8, 0), event.getVelocity());
        verify(victim, never()).setVelocity(any());
    }

    @Test public void exclusionBetweenPrioritiesRevokesProtectionWithoutASettingsReload() {
        pending(melee);
        motion.set(new Vector(0.4, 0.36, 0));
        var event = new PlayerVelocityEvent(victim, new Vector());
        classic.beginVelocity(event);
        store.excluded(victim.getUniqueId(), true);
        event.setVelocity(new Vector(0, 0.8, 0));
        classic.velocity(event);
        assertEquals(new Vector(0, 0.8, 0), event.getVelocity());
        verify(victim, never()).setVelocity(any());
    }

    @Test public void compatibilityEntryPointCannotProtectModernOrDisabledMode() {
        for (Settings inactive : List.of(
                settings.withMode(CombatMode.MODERN, ClassicSettings.defaults()),
                new Settings(false, false, true, true, Set.of(), Map.of(), Map.of(), Map.of(),
                        CombatMode.CLASSIC, ClassicSettings.defaults()))) {
            settings = inactive;
            pending(melee);
            motion.set(new Vector(0.4, 0.36, 0));
            var event = new PlayerVelocityEvent(victim, new Vector(0, 0.8, 0));
            classic.beginVelocity(event);
            classic.velocity(event);
            assertEquals(new Vector(0, 0.8, 0), event.getVelocity());
        }
        verify(victim, never()).setVelocity(any());
    }

    @Test public void olderVelocityDispatchCannotConsumeANewerMeleeHit() {
        pending(rod);
        motion.set(new Vector(0.4, 0.36, 0));
        var older = new PlayerVelocityEvent(victim, new Vector());
        classic.beginVelocity(older);
        pending(melee);
        classic.velocity(older);
        assertEquals(new Vector(), older.getVelocity());
        Vector current = new Vector(-0.2, 0.3, 0.3);
        motion.set(current);
        var newer = new PlayerVelocityEvent(victim, new Vector());
        classic.beginVelocity(newer);
        motion.set(new Vector(-0.2, 0.8, 0.3));
        classic.velocity(newer);
        assertEquals(current, newer.getVelocity());
        assertEquals(current, motion.get());
        verify(victim, times(1)).setVelocity(current);
    }

    @Test public void expiredMeleeProtectionDoesNotReplayOnDelayedDispatch() {
        pending(melee);
        tick.incrementAndGet();
        motion.set(new Vector(0.3, 0.2, 0));
        var event = new PlayerVelocityEvent(victim, new Vector(0.3, 0.2, 0));
        classic.beginVelocity(event);
        event.setVelocity(new Vector(0.3, 0.8, 0));
        classic.velocity(event);
        assertEquals(new Vector(0.3, 0.8, 0), event.getVelocity());
        verify(victim, never()).setVelocity(any());
    }

    @Test public void deferredRodProtectionUsesCurrentMotionOnceWithinItsExistingWindow() {
        pending(rod);
        tick.incrementAndGet();
        Vector current = new Vector(0.3, 0.24, 0);
        motion.set(current);
        var event = new PlayerVelocityEvent(victim, new Vector(0.3, 0.24, 0));
        classic.beginVelocity(event);
        motion.set(new Vector(0.3, 0.8, 0));
        classic.velocity(event);
        assertEquals(current, event.getVelocity());
        assertEquals(current, motion.get());
        var later = new PlayerVelocityEvent(victim, new Vector(0.2, 0.1, 0));
        classic.beginVelocity(later);
        classic.velocity(later);
        assertEquals(new Vector(0.2, 0.1, 0), later.getVelocity());
        verify(victim, times(1)).setVelocity(current);
    }
}

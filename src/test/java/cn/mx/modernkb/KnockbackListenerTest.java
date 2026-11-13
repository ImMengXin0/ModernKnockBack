package cn.mx.modernkb;

import cn.mx.modernkb.api.*;
import cn.mx.modernkb.combat.*;
import cn.mx.modernkb.config.*;
import cn.mx.modernkb.profiler.KnockbackProfiler;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.Vector;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

public class KnockbackListenerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private MockedStatic<Bukkit> bukkit;
    private PluginManager manager;
    private Player victim;
    private CombatTracker tracker;
    private PlayerStore players;
    private Settings settings;
    private KnockbackListener listener;
    @Before public void setup() {
        bukkit = mockStatic(Bukkit.class); manager = mock(PluginManager.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
        victim = mock(Player.class); UUID id = UUID.randomUUID(); when(victim.getUniqueId()).thenReturn(id);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(victim)).thenReturn(true);
        tracker = mock(CombatTracker.class); when(tracker.kind(victim)).thenReturn(CombatantKind.PLAYER);
        when(tracker.context(eq(victim), isNull(), anyString(), anyBoolean()))
                .thenReturn(KnockbackMathTest.hit(HitType.MELEE, true, false, false, 1, false));
        players = new PlayerStore(temporary.getRoot().toPath().resolve("players.yml"));
        settings = new Settings(true, false, true, true, Set.of(),
                Map.of("default", KnockbackMathTest.profile("horizontal.ground", 2, "vertical.ground", 3)), Map.of(), Map.of());
        listener = new KnockbackListener(() -> settings, players, tracker, () -> false);
    }
    @After public void close() { bukkit.close(); }
    private EntityKnockbackEvent event() { return new EntityKnockbackEvent(victim, EntityKnockbackEvent.Cause.ENTITY_ATTACK, new Vector(0.2, 0.3, -0.1)); }
    @Test public void changesNativeImpulseOnceWithoutReadingOrWritingVictimVelocity() {
        var event = event(); listener.knockback(event);
        assertEquals(0.4, event.getKnockback().getX(), 1e-12); assertEquals(0.9, event.getKnockback().getY(), 1e-12);
        verify(victim, never()).getVelocity(); verify(victim, never()).setVelocity(any());
        assertEquals(1, listener.handled());
    }
    @Test public void profilerReceivesActualConfigurationAndAppliedVectorsWithoutAlteringCalculation() {
        var profiler = mock(KnockbackProfiler.class);
        listener = new KnockbackListener(() -> settings, players, tracker, () -> false, null, profiler);
        var event = event(); listener.knockback(event);
        verify(profiler).context(same(event), any(HitContext.class), same(settings), same(settings), eq("default"));
        verify(profiler).calculation(same(event), eq(event.getKnockback()), eq(false), anyString());
        verify(profiler).applied(same(event), eq(event.getKnockback()));
        verify(victim, never()).setVelocity(any());
    }
    @Test public void cancelledApiModificationIsObservedButNeverMarkedAsApplied() {
        var profiler = mock(KnockbackProfiler.class);
        listener = new KnockbackListener(() -> settings, players, tracker, () -> false, null, profiler);
        doAnswer(invocation -> { if (invocation.getArgument(0) instanceof KnockbackModifyEvent e) e.setCancelled(true); return null; }).when(manager).callEvent(any());
        var event = event(); listener.knockback(event);
        verify(profiler).calculation(same(event), any(), eq(false), anyString());
        verify(profiler, never()).applied(any(), any()); assertEquals(new Vector(.2, .3, -.1), event.getKnockback());
    }
    @Test public void cancelledSelectionLeavesOriginalImpulse() {
        doAnswer(invocation -> { if (invocation.getArgument(0) instanceof KnockbackProfileEvent e) e.setCancelled(true); return null; }).when(manager).callEvent(any());
        var event = event(); listener.knockback(event); assertEquals(new Vector(0.2, 0.3, -0.1), event.getKnockback());
    }
    @Test public void cancelledModificationLeavesOriginalImpulse() {
        doAnswer(invocation -> { if (invocation.getArgument(0) instanceof KnockbackModifyEvent e) e.setCancelled(true); return null; }).when(manager).callEvent(any());
        var event = event(); listener.knockback(event); assertEquals(new Vector(0.2, 0.3, -0.1), event.getKnockback());
    }
    @Test public void invalidApiProfileLeavesNative() {
        doAnswer(invocation -> { if (invocation.getArgument(0) instanceof KnockbackProfileEvent e) e.setProfile("missing"); return null; }).when(manager).callEvent(any());
        var event = event(); listener.knockback(event); assertEquals(new Vector(0.2, 0.3, -0.1), event.getKnockback());
    }
    @Test public void syncVerticalOwnershipIsEnforcedAfterApiListeners() {
        when(tracker.context(eq(victim), isNull(), anyString(), anyBoolean())).thenReturn(KnockbackMathTest.hit(HitType.MELEE, true, false, false, 1, true));
        doAnswer(invocation -> { if (invocation.getArgument(0) instanceof KnockbackModifyEvent e) e.setImpulse(new Vector(0.8, 9, 0.4)); return null; }).when(manager).callEvent(any());
        var event = event(); listener.knockback(event);
        assertEquals(new Vector(0.8, 0.3, 0.4), event.getKnockback());
    }
    @Test public void excludesPersistedVictimAndDoesNotPublishEvents() {
        players.excluded(victim.getUniqueId(), true);
        var event = event(); listener.knockback(event);
        verifyNoInteractions(manager); assertEquals(0, listener.handled());
    }
    @Test public void wrongRegionDoesNotInspectVictimState() {
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(victim)).thenReturn(false);
        clearInvocations(victim, tracker);
        listener.knockback(event()); verifyNoInteractions(tracker); verify(victim, never()).getUniqueId();
    }
    @Test public void onlyRegistersPaperKnockbackAndHonorsCancelledEvents() {
        int listeners = 0;
        for (var method : KnockbackListener.class.getDeclaredMethods()) if (method.isAnnotationPresent(EventHandler.class)) {
            listeners++; assertEquals(EntityKnockbackEvent.class, method.getParameterTypes()[0]);
            assertTrue(method.getAnnotation(EventHandler.class).ignoreCancelled());
        }
        assertEquals(1, listeners);
    }
    private void enableNative() {
        settings = new Settings(true, false, true, true, Set.of(), Map.of("default", KnockbackMathTest.profile(
                "native.enabled", true, "horizontal.ground", 0.9)), Map.of(), Map.of());
        when(tracker.context(eq(victim), any(), anyString(), anyBoolean()))
                .thenReturn(KnockbackMathTest.hit(HitType.MELEE, true, false, false, 1, false));
        when(victim.getVelocity()).thenReturn(new Vector(1, 0, 0));
    }
    private com.destroystokyo.paper.event.entity.EntityKnockbackByEntityEvent nativeEvent(EntityKnockbackEvent.Cause cause) {
        return new com.destroystokyo.paper.event.entity.EntityKnockbackByEntityEvent(victim, mock(Player.class), cause, 0.4f, new Vector(-0.1, 0.4, 0));
    }
    @Test public void nativeEventSeparatesPushAndMomentumWithoutSettingVelocity() {
        enableNative(); var event = nativeEvent(EntityKnockbackEvent.Cause.ENTITY_ATTACK);
        listener.knockback(event);
        assertEquals(-0.14, event.getKnockback().getX(), 1e-12);
        verify(victim, never()).setVelocity(any()); assertEquals(1, listener.nativeHandled());
        var captured = org.mockito.ArgumentCaptor.forClass(Event.class); verify(manager, times(2)).callEvent(captured.capture());
        assertTrue(((KnockbackModifyEvent)captured.getAllValues().get(1)).isNativeCalculation());
    }
    @Test public void explosionNeverReconstructsEvenIfVectorLooksLikeOrdinaryKnockback() {
        enableNative(); var event = nativeEvent(EntityKnockbackEvent.Cause.EXPLOSION);
        listener.knockback(event);
        assertEquals(-0.09, event.getKnockback().getX(), 1e-12); verify(victim, never()).getVelocity();
        assertEquals(1, listener.nativeFallbacks()); assertEquals(0, listener.nativeMismatches());
    }
    @Test public void arrowPunchPushUsesImpulsePath() {
        enableNative();
        var event = new io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent(victim, EntityKnockbackEvent.Cause.PUSH,
                mock(org.bukkit.entity.AbstractArrow.class), new Vector(0.6, 0.1, 0));
        listener.knockback(event);
        assertEquals(0.54, event.getKnockback().getX(), 1e-12); verify(victim, never()).getVelocity();
        assertEquals(1, listener.nativeFallbacks());
    }
    @Test public void mismatchedFormulaIsCountedAndCanBeObservedByIntegrations() {
        enableNative(); var event = nativeEvent(EntityKnockbackEvent.Cause.ENTITY_ATTACK);
        event.setKnockback(new Vector(-0.1, 0.2, 0)); listener.knockback(event);
        assertEquals(1, listener.nativeMismatches()); assertEquals(0, listener.nativeHandled());
        var captured = org.mockito.ArgumentCaptor.forClass(Event.class); verify(manager, times(2)).callEvent(captured.capture());
        assertEquals("vertical-mismatch", ((KnockbackModifyEvent)captured.getAllValues().get(1)).getFallbackReason());
    }
    @Test public void nativeSyncPolicyStillWinsOverFinalApiChanges() {
        enableNative();
        when(tracker.context(eq(victim), any(), anyString(), anyBoolean()))
                .thenReturn(KnockbackMathTest.hit(HitType.MELEE, true, false, false, 1, true));
        doAnswer(invocation -> { if (invocation.getArgument(0) instanceof KnockbackModifyEvent e) e.setImpulse(new Vector(0, 9, 0)); return null; }).when(manager).callEvent(any());
        var event = nativeEvent(EntityKnockbackEvent.Cause.ENTITY_ATTACK); listener.knockback(event);
        assertEquals(0.4, event.getKnockback().getY(), 0); assertEquals(1, listener.nativeHandled());
    }
    @Test public void scopedRodSupportsSourceLessNativeEvent() {
        enableNative();
        when(tracker.context(eq(victim), any(), anyString(), anyBoolean()))
                .thenReturn(KnockbackMathTest.hit(HitType.FISHING_ROD, true, false, false, 1, false));
        var event = new EntityKnockbackEvent(victim, EntityKnockbackEvent.Cause.UNKNOWN, new Vector(-0.3, 0.2, 0));
        listener.knockback(event);
        assertEquals(-0.32, event.getKnockback().getX(), 1e-12); assertEquals(1, listener.nativeHandled());
    }
    private ClassicCombat modernItems(String... names) {
        Settings modern = new Settings(true, false, true, true, Set.of(), Map.of(
                "default", KnockbackMathTest.profile(),
                "heavy", KnockbackMathTest.profile("horizontal.ground", 1.5, "vertical.ground", .5)),
                Map.of("mace", "heavy", "spear", "heavy"), Map.of());
        settings = settings.withMode(CombatMode.CLASSIC, ClassicSettings.defaults()).withItems(new ItemCombat(Set.of(names), modern));
        ClassicCombat classic = mock(ClassicCombat.class);
        listener = new KnockbackListener(() -> settings, players, tracker, () -> true, classic);
        return classic;
    }
    private void weaponHit(HitType type, String weapon) {
        UUID attacker = UUID.randomUUID();
        UUID target = victim.getUniqueId();
        when(tracker.context(eq(victim), isNull(), anyString(), anyBoolean())).thenReturn(new HitContext(
                target, attacker, attacker, type, CombatantKind.PLAYER, CombatantKind.PLAYER,
                "ENTITY_ATTACK", true, false, false, .25, false, weapon));
    }
    @Test public void selectedWeaponsUseModernProfilesAndApiModeWhileRespectingSync() {
        var classic = modernItems("MACE", "IRON_SPEAR");
        for (var entry : Map.of("MACE", HitType.MACE, "IRON_SPEAR", HitType.SPEAR).entrySet()) {
            clearInvocations(manager, classic); weaponHit(entry.getValue(), entry.getKey());
            var event = event(); listener.knockback(event);
            assertEquals(.3, event.getKnockback().getX(), 1e-12);
            assertEquals(-.15, event.getKnockback().getZ(), 1e-12);
            assertEquals(.3, event.getKnockback().getY(), 0); // Modern preserve-y policy, not Classic.
            var captured = org.mockito.ArgumentCaptor.forClass(Event.class); verify(manager, times(2)).callEvent(captured.capture());
            assertEquals(CombatMode.MODERN, ((KnockbackProfileEvent)captured.getAllValues().getFirst()).getMode());
            assertEquals("heavy", ((KnockbackProfileEvent)captured.getAllValues().getFirst()).getProfile());
            assertEquals(CombatMode.MODERN, ((KnockbackModifyEvent)captured.getAllValues().getLast()).getMode());
            verify(classic).releaseVelocity(victim); verify(classic, never()).calculate(any(), any(), any(), any());
        }
    }
    @Test public void unlistedWeaponsAndProjectilesStayClassicAndReloadCanRemoveExceptions() {
        var classic = modernItems("MACE");
        for (var entry : List.of(Map.entry("IRON_SWORD", HitType.MELEE), Map.entry("MACE", HitType.FISHING_ROD), Map.entry("MACE", HitType.ARROW))) {
            // A held weapon cannot convert a scoped rod or arrow into a modern melee hit.
            clearInvocations(classic); weaponHit(entry.getValue(), entry.getKey());
            var event = event(); listener.knockback(event);
            assertEquals(.4, event.getKnockback().getX(), 1e-12);
            verify(classic).calculate(eq(event), any(), any(), same(settings));
            verify(classic, never()).releaseVelocity(any());
        }
        settings = settings.withItems(ItemCombat.empty()); weaponHit(HitType.MACE, "MACE");
        var event = event(); listener.knockback(event); assertEquals(.4, event.getKnockback().getX(), 1e-12);
    }
    @Test public void selectedItemsUseModernBeforeSyncOrDisabledWithoutClassicFallback() {
        var classic = modernItems("MACE"); weaponHit(HitType.MACE, "MACE");
        var modern = settings.items().modern();
        settings = settings.withItems(new ItemCombat(Set.of("MACE"), new Settings(true, false, true, false,
                modern.botMetadata(), modern.profiles(), modern.routes(), modern.matchups())));
        var event = event(); listener.knockback(event); assertEquals(.15, event.getKnockback().getY(), 1e-12);
        settings = settings.withItems(new ItemCombat(Set.of("MACE"), new Settings(false, false, true, false,
                modern.botMetadata(), modern.profiles(), modern.routes(), modern.matchups())));
        clearInvocations(manager, classic);
        event = event(); listener.knockback(event); assertEquals(new Vector(.2, .3, -.1), event.getKnockback());
        verifyNoInteractions(manager); verify(classic).releaseVelocity(victim);
        verify(classic, never()).calculate(any(), any(), any(), any());
    }
}

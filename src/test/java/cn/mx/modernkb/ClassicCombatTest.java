package cn.mx.modernkb;

import cn.mx.modernkb.api.*;
import cn.mx.modernkb.combat.*;
import cn.mx.modernkb.config.*;
import cn.mx.modernkb.compat.RodVelocity;
import cn.mx.modernkb.compat.RodFeedback;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.threadedregions.scheduler.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.persistence.*;
import org.bukkit.plugin.*;
import org.bukkit.util.Vector;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ClassicCombatTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private MockedStatic<Bukkit> bukkit;
    private ClassicCombat classic;
    private KnockbackListener listener;
    private Settings settings;
    private Player victim, attacker;
    private PlayerStore store;
    private CombatTracker tracker;
    private Plugin plugin;
    private PluginManager manager;
    private AttributeInstance speed;
    private Map<NamespacedKey,Object> persistent;
    private AtomicInteger immunity;
    private Map<net.kyori.adventure.key.Key,AttributeModifier> modifiers;
    private DamageSource.Builder rodSourceBuilder;
    private DamageSource rodSource;

    @BeforeClass public static void bootstrapAttributes() {
        // Lightweight registries for Bukkit constants; no live server.
        try (var accessStatic = mockStatic(RegistryAccess.class)) {
            RegistryAccess access = mock(RegistryAccess.class);
            accessStatic.when(RegistryAccess::registryAccess).thenReturn(access);
            var registryEntries = new HashMap<String, Keyed>();
            org.mockito.stubbing.Answer<Object> registries = call -> {
                Class<?> entryType = call.getArgument(0) == DamageType.class || call.getArgument(0) == RegistryKey.DAMAGE_TYPE
                        ? DamageType.class : call.getArgument(0) == Sound.class || call.getArgument(0) == RegistryKey.SOUND_EVENT
                        ? Sound.class : Attribute.class;
                return new Registry<Keyed>() {
                @Override public Keyed get(NamespacedKey key) {
                    return registryEntries.computeIfAbsent(entryType.getName() + "/" + key,
                            ignored -> (Keyed)Proxy.newProxyInstance(entryType.getClassLoader(),
                            new Class<?>[]{entryType}, (proxy, method, args) -> switch (method.getName()) {
                                case "getKey" -> key;
                                case "key" -> key;
                                case "equals" -> proxy == args[0];
                                case "hashCode" -> key.hashCode();
                                case "toString", "getTranslationKey", "translationKey", "name" -> key.toString();
                                default -> null;
                            }));
                }
                @Override public Iterator<Keyed> iterator() { return Collections.emptyIterator(); }
                @Override public java.util.stream.Stream<Keyed> stream() { return java.util.stream.Stream.empty(); }
                @Override public int size() { return registryEntries.size(); }
                @Override public NamespacedKey getKey(Keyed value) { return value.getKey(); }
                @Override public boolean hasTag(io.papermc.paper.registry.tag.TagKey<Keyed> key) { return false; }
                @Override public io.papermc.paper.registry.tag.Tag<Keyed> getTag(io.papermc.paper.registry.tag.TagKey<Keyed> key) { return null; }
                @Override public Collection<io.papermc.paper.registry.tag.Tag<Keyed>> getTags() { return List.of(); }
                @Override public java.util.stream.Stream<NamespacedKey> keyStream() { return registryEntries.values().stream().map(Keyed::getKey); }
            }; };
            doAnswer(registries).when(access).getRegistry(any(Class.class));
            doAnswer(registries).when(access).getRegistry(any(RegistryKey.class));
            assertNotNull(Attribute.ATTACK_SPEED);
            assertNotNull(DamageType.THROWN);
            assertNotNull(Sound.ENTITY_PLAYER_HURT);
        }
    }
    @Before public void setup() {
        bukkit = mockStatic(Bukkit.class); manager = mock(PluginManager.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
        UnsafeValues unsafe = mock(UnsafeValues.class);
        bukkit.when(Bukkit::getUnsafe).thenReturn(unsafe);
        rodSourceBuilder = mock(DamageSource.Builder.class, RETURNS_SELF);
        rodSource = mock(DamageSource.class);
        when(unsafe.createDamageSourceBuilder(eq(DamageType.THROWN))).thenReturn(rodSourceBuilder);
        when(rodSourceBuilder.build()).thenReturn(rodSource);
        plugin = mock(Plugin.class); when(plugin.namespace()).thenReturn("modernknockback"); when(plugin.getName()).thenReturn("ModernKnockback");
        when(plugin.isEnabled()).thenReturn(true);
        victim = player(1); attacker = player(0);
        tracker = mock(CombatTracker.class);
        when(tracker.kind(victim)).thenReturn(CombatantKind.PLAYER); when(tracker.kind(attacker)).thenReturn(CombatantKind.PLAYER);
        var source = new CombatTracker.Source(attacker.getUniqueId(), attacker.getUniqueId(), CombatantKind.PLAYER, HitType.MELEE, false, 1);
        when(tracker.source(eq(attacker), any())).thenReturn(source);
        var hit = new HitContext(victim.getUniqueId(), attacker.getUniqueId(), attacker.getUniqueId(), HitType.MELEE,
                CombatantKind.PLAYER, CombatantKind.PLAYER, "ENTITY_ATTACK", true, false, false, 1, false);
        when(tracker.context(eq(victim), any(), anyString(), anyBoolean())).thenReturn(hit);
        store = new PlayerStore(temporary.getRoot().toPath().resolve("players.yml"));
        settings = new Settings(true, false, true, true, Set.of(), Map.of("default", KnockbackMathTest.profile()), Map.of(), Map.of())
                .withMode(CombatMode.CLASSIC, ClassicSettings.defaults());
        classic = new ClassicCombat(plugin, () -> settings, store, tracker);
        listener = new KnockbackListener(() -> settings, store, tracker, () -> true, classic);
        speed = mock(AttributeInstance.class);
        modifiers = new HashMap<>();
        when(speed.getModifier(any(net.kyori.adventure.key.Key.class))).thenAnswer(call -> modifiers.get(call.getArgument(0)));
        doAnswer(call -> { AttributeModifier value = call.getArgument(0); modifiers.put(value.getKey(), value); return null; }).when(speed).addTransientModifier(any());
        doAnswer(call -> { modifiers.remove(call.getArgument(0)); return null; }).when(speed).removeModifier(any(net.kyori.adventure.key.Key.class));
        when(victim.getAttribute(Attribute.ATTACK_SPEED)).thenReturn(speed);
        persistent = new HashMap<>();
        PersistentDataContainer data = victim.getPersistentDataContainer();
        when(data.has(any(NamespacedKey.class), eq(PersistentDataType.INTEGER))).thenAnswer(call -> persistent.containsKey(call.getArgument(0)));
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.INTEGER))).thenAnswer(call -> persistent.get(call.getArgument(0)));
        doAnswer(call -> { persistent.put(call.getArgument(0), call.getArgument(2)); return null; }).when(data).set(any(), eq(PersistentDataType.INTEGER), any());
        doAnswer(call -> { persistent.remove(call.getArgument(0)); return null; }).when(data).remove(any());
        immunity = new AtomicInteger(18);
        when(victim.getMaximumNoDamageTicks()).thenAnswer(call -> immunity.get());
        doAnswer(call -> { immunity.set(call.getArgument(0)); return null; }).when(victim).setMaximumNoDamageTicks(anyInt());
    }
    private Player player(double x) {
        Player player = mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(new Location(null, x, 64, 0));
        when(player.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        when(player.getVelocity()).thenReturn(new Vector()); when(player.getTicksLived()).thenReturn(100);
        when(player.isOnGround()).thenReturn(true); when(player.isValid()).thenReturn(true);
        EntityScheduler scheduler = mock(EntityScheduler.class); when(player.getScheduler()).thenReturn(scheduler);
        when(scheduler.run(any(), any(), any())).thenReturn(mock(ScheduledTask.class));
        when(scheduler.runDelayed(any(), any(), any(), anyLong())).thenReturn(mock(ScheduledTask.class));
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(true);
        return player;
    }
    @After public void close() { if (bukkit != null) bukkit.close(); }
    private EntityDamageByEntityEvent damage() {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(victim); when(event.getDamager()).thenReturn(attacker);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_ATTACK);
        when(event.getDamageSource()).thenReturn(mock(DamageSource.class)); when(event.getFinalDamage()).thenReturn(3d);
        when(victim.getLastDamageCause()).thenReturn(event);
        return event;
    }
    private EntityKnockbackEvent knockback() {
        return new com.destroystokyo.paper.event.entity.EntityKnockbackByEntityEvent(victim, attacker,
                EntityKnockbackEvent.Cause.ENTITY_ATTACK, 0.4f, new Vector(0.4, 0.4, 0));
    }
    @Test public void nativeMeleeFallbackCannotRepeatKnockbackInsideNineTickWindow() {
        settings = settings.withMode(CombatMode.CLASSIC, new ClassicSettings(9, 9, true, true, false, true, 2, 9));
        doAnswer(call -> {
            if (call.getArgument(0) instanceof KnockbackProfileEvent selection) selection.setCancelled(true);
            return null;
        }).when(manager).callEvent(any());
        // A native impulse may have no positive damage record (zero-damage combat plugin).
        var first = knockback();
        listener.knockback(first); classic.finishedKnockback(first);
        assertFalse(first.isCancelled());
        for (int tick = 100; tick < 109; tick++) {
            when(victim.getTicksLived()).thenReturn(tick);
            var repeated = knockback(); listener.knockback(repeated); classic.finishedKnockback(repeated);
            assertTrue("Native fallback bypassed the hurt window at tick " + tick, repeated.isCancelled());
            var leftClick = new PrePlayerAttackEntityEvent(attacker, victim, true); classic.beforeAttack(leftClick);
            assertTrue(leftClick.isCancelled());
        }
        when(victim.getTicksLived()).thenReturn(109);
        var boundary = knockback(); listener.knockback(boundary); classic.finishedKnockback(boundary);
        assertFalse(boundary.isCancelled());
    }
    @Test public void nativeMeleeFallbackDeduplicatesTheAcceptedDamageComponent() {
        doAnswer(call -> {
            if (call.getArgument(0) instanceof KnockbackModifyEvent modification) modification.setCancelled(true);
            return null;
        }).when(manager).callEvent(any());
        var damage = damage(); classic.acceptedDamage(damage);
        var first = knockback(); listener.knockback(first); classic.finishedKnockback(first);
        assertFalse(first.isCancelled());
        var extraSprint = knockback(); listener.knockback(extraSprint); classic.finishedKnockback(extraSprint);
        assertTrue("Declining a custom vector must not re-enable the second native component", extraSprint.isCancelled());
    }
    @Test public void customNativeZeroHitCannotRepeatOnTheNextTick() {
        var first = knockback(); listener.knockback(first); classic.finishedKnockback(first);
        when(victim.getTicksLived()).thenReturn(101);
        var nextTick = knockback(); listener.knockback(nextTick); classic.finishedKnockback(nextTick);
        assertTrue(nextTick.isCancelled());
        assertEquals(1, classic.applied());
    }
    @Test public void lateCancelledNativeFallbackDoesNotLeaveASyntheticWindow() {
        doAnswer(call -> {
            if (call.getArgument(0) instanceof KnockbackProfileEvent selection) selection.setCancelled(true);
            return null;
        }).when(manager).callEvent(any());
        var first = knockback(); listener.knockback(first); classic.finishedKnockback(first);
        first.setCancelled(true); // A later MONITOR listener rejected the actual impulse.
        var leftClick = new PrePlayerAttackEntityEvent(attacker, victim, true); classic.beforeAttack(leftClick);
        assertFalse(leftClick.isCancelled());
        var replacement = knockback(); listener.knockback(replacement); classic.finishedKnockback(replacement);
        assertFalse(replacement.isCancelled());
    }
    @Test public void rodFeedbackAndBothAttackPathsShareConfiguredNineTickWindow() {
        settings = settings.withMode(CombatMode.CLASSIC, new ClassicSettings(9, 9, true, true, false, true, 2, 9));
        RodVelocity.Transport transport = mock(RodVelocity.Transport.class);
        RodFeedback feedback = mock(RodFeedback.class);
        when(feedback.play(any(), any(), any())).thenReturn(true);
        classic = new ClassicCombat(plugin, () -> settings, store, tracker, RodVelocity.immediate(transport), feedback);
        listener = new KnockbackListener(() -> settings, store, tracker, () -> false, classic);
        FishHook first = rodHook(); rodDamage(first, event -> {});
        assertEquals(ClassicCombat.RodResult.APPLIED, classic.rodImpactResult(first, attacker, victim, 0.4));
        verify(victim).setNoDamageTicks(18);
        var order = inOrder(transport, feedback);
        order.verify(transport).send(eq(victim), any());
        order.verify(feedback).play(eq(victim), eq(rodSource), any());
        for (int tick = 100; tick < 109; tick++) {
            when(victim.getTicksLived()).thenReturn(tick);
            assertEquals(ClassicCombat.RodResult.REJECTED, classic.rodImpactResult(rodHook(), attacker, victim, 0.4));
            var apiRod = new EntityKnockbackEvent(victim, EntityKnockbackEvent.Cause.UNKNOWN, new Vector(0.5, 0.34, 0));
            listener.knockback(apiRod); classic.finishedKnockback(apiRod);
            assertTrue("Explicit rod API must share the accepted hook's window", apiRod.isCancelled());
            var leftClick = new PrePlayerAttackEntityEvent(attacker, victim, true); classic.beforeAttack(leftClick);
            assertTrue(leftClick.isCancelled());
        }
        verify(feedback, times(1)).play(any(), any(), any()); verify(transport, times(1)).send(any(), any());
        when(victim.getTicksLived()).thenReturn(109);
        FishHook boundary = rodHook(); rodDamage(boundary, event -> {});
        assertEquals(ClassicCombat.RodResult.APPLIED, classic.rodImpactResult(boundary, attacker, victim, 0.4));
        verify(feedback, times(2)).play(any(), any(), any());
        verify(victim, never()).setHealth(anyDouble());
        assertEquals("Classic rod: contacts=11 | accepted=2 | rejected=9 | feedback=2", classic.rodStatus());
    }
    @Test public void coincidentCriticalHitNeverUsesRandomNativeKnockbackDirection() {
        when(attacker.getLocation()).thenReturn(new Location(null, 0, 64, 0, 0, 80));
        when(victim.getLocation()).thenReturn(new Location(null, 0, 64, 0));
        var damage = damage(); when(damage.isCritical()).thenReturn(true);
        classic.acceptedDamage(damage);
        var critical = new HitContext(victim.getUniqueId(), attacker.getUniqueId(), attacker.getUniqueId(), HitType.MELEE,
                CombatantKind.PLAYER, CombatantKind.PLAYER, "ENTITY_ATTACK", true, false, true, 1, false);
        for (Vector nativePush : new Vector[]{new Vector(0.4, 0.4, 0), new Vector(-0.4, 0.4, 0), new Vector(0, 0.4, -0.4)}) {
            var event = new com.destroystokyo.paper.event.entity.EntityKnockbackByEntityEvent(victim, attacker,
                    EntityKnockbackEvent.Cause.ENTITY_ATTACK, 0.4f, nativePush);
            var calculation = classic.calculate(event, critical, KnockbackMathTest.profile(), settings);
            assertEquals(0, calculation.impulse().getX(), 1e-12);
            assertEquals(0.4, calculation.impulse().getZ(), 1e-12);
        }
    }
    @Test public void nearOverlappingCriticalHitKeepsItsFacingAcrossHeightAndPositionNoise() {
        var critical = new HitContext(victim.getUniqueId(), attacker.getUniqueId(), attacker.getUniqueId(), HitType.MELEE,
                CombatantKind.PLAYER, CombatantKind.PLAYER, "ENTITY_ATTACK", true, false, true, 1, false);
        for (double height : new double[]{0, 0.25, 1.1}) {
            for (double noise : new double[]{-0.001, 0, 0.001}) {
                when(attacker.getLocation()).thenReturn(new Location(null, 0, 64 + height, 0, -90, 85));
                when(victim.getLocation()).thenReturn(new Location(null, noise, 64, -noise));
                var damage = damage(); when(damage.isCritical()).thenReturn(true);
                classic.acceptedDamage(damage);
                // Later look/position updates must not rotate a previously accepted hit.
                when(attacker.getLocation()).thenReturn(new Location(null, 2, 64, 2, 90, -70));
                var calculation = classic.calculate(knockback(), critical, KnockbackMathTest.profile(), settings);
                assertEquals("height=" + height + ", noise=" + noise, 0.4, calculation.impulse().getX(), 1e-12);
                assertEquals(0, calculation.impulse().getZ(), 1e-12);
            }
        }
    }
    @Test public void criticalOverlapSendsOneStableVectorForPlayersAndBotsWithSprintBonus() {
        UUID source = attacker.getUniqueId();
        UUID target = victim.getUniqueId();
        when(tracker.source(eq(attacker), any())).thenReturn(new CombatTracker.Source(source, source, CombatantKind.PLAYER, HitType.MELEE, true, 1));
        int tick = 100;
        for (CombatantKind kind : new CombatantKind[]{CombatantKind.PLAYER, CombatantKind.BOT}) {
            when(tracker.kind(victim)).thenReturn(kind);
            for (boolean grounded : new boolean[]{true, false}) {
                when(victim.getTicksLived()).thenReturn(tick); tick += 10;
                when(attacker.getLocation()).thenReturn(new Location(null, 0, 65, 0, -90, 85));
                when(victim.getLocation()).thenReturn(new Location(null, -0.001, 64, 0.001));
                Vector before = new Vector(-0.3, -0.2, 0.7);
                when(victim.getVelocity()).thenReturn(before);
                when(tracker.context(eq(victim), any(), anyString(), anyBoolean())).thenReturn(new HitContext(
                        target, source, source, HitType.MELEE, CombatantKind.PLAYER, kind,
                        "ENTITY_ATTACK", grounded, true, true, 1, false));
                var damage = damage(); when(damage.isCritical()).thenReturn(true); classic.acceptedDamage(damage);
                var first = knockback(); listener.knockback(first); classic.finishedKnockback(first);
                Vector after = before.clone().add(first.getKnockback());
                assertEquals(0.9, after.getX(), 1e-12);
                assertEquals(0, after.getZ(), 1e-12);
                assertEquals((grounded ? 0.36075 : 0.24775) + 0.1, after.getY(), 1e-12);
                when(victim.getVelocity()).thenReturn(after);
                var extra = knockback(); listener.knockback(extra); assertTrue(extra.isCancelled());
                var packet = new PlayerVelocityEvent(victim, new Vector(-0.4, 8, -0.4));
                classic.beginVelocity(packet); classic.velocity(packet); assertEquals(after, packet.getVelocity());
            }
        }
        assertEquals(4, classic.applied());
    }
    @Test public void switchBackRestoresOriginalImmunityAndRemovesOnlyOwnedModifier() {
        classic.reconcile(victim); classic.reconcile(victim);
        assertEquals(20, immunity.get()); assertEquals(1, modifiers.size());
        verify(speed, times(1)).addTransientModifier(any()); verify(speed, never()).setBaseValue(anyDouble());
        settings = settings.withMode(CombatMode.MODERN, ClassicSettings.defaults()); classic.reconcile(victim);
        assertEquals(18, immunity.get()); assertTrue(modifiers.isEmpty()); assertTrue(persistent.isEmpty());
    }
    @Test public void classicCombatNeverDisablesProjectileTargeting() {
        AtomicBoolean collidable = new AtomicBoolean(true);
        when(victim.isCollidable()).thenAnswer(call -> collidable.get());
        doAnswer(call -> { collidable.set(call.getArgument(0)); return null; }).when(victim).setCollidable(anyBoolean());
        classic.reconcile(victim);
        assertTrue(collidable.get());
        settings = settings.withMode(CombatMode.MODERN, ClassicSettings.defaults());
        classic.reconcile(victim);
        assertTrue(collidable.get());
        verify(victim, never()).setCollidable(anyBoolean());
    }
    @Test public void externalImmunityChangesAreNotOverwrittenOnRestore() {
        classic.reconcile(victim); immunity.set(40);
        settings = settings.withMode(CombatMode.MODERN, ClassicSettings.defaults()); classic.reconcile(victim);
        assertEquals(40, immunity.get());
    }
    @Test public void eachDamageHasOneClassicVectorAndExtraNativeComponentIsCancelled() {
        classic.acceptedDamage(damage());
        var first = knockback(); listener.knockback(first); classic.finishedKnockback(first);
        assertEquals(new Vector(0.4, 0.36075, 0), first.getKnockback());
        var second = knockback(); listener.knockback(second);
        assertTrue(second.isCancelled()); assertEquals(1, classic.applied()); assertEquals(1, classic.preventedComponents());
        verify(victim, never()).setVelocity(any());
    }
    @Test public void earlyAttacksDoNotResetCooldownOrDelayNextAcceptedAttack() {
        classic.acceptedDamage(damage());
        when(victim.getTicksLived()).thenReturn(109);
        var early = new PrePlayerAttackEntityEvent(attacker, victim, true); classic.beforeAttack(early);
        assertTrue(early.isCancelled()); verify(victim, never()).setNoDamageTicks(0); verify(attacker, never()).resetCooldown();
        when(victim.getTicksLived()).thenReturn(110);
        var allowed = new PrePlayerAttackEntityEvent(attacker, victim, true); classic.beforeAttack(allowed);
        assertFalse(allowed.isCancelled());
    }
    @Test public void damageCancellationAfterMonitorDoesNotDeduplicateOrReplayOldVector() {
        var damage = damage(); classic.acceptedDamage(damage); when(damage.isCancelled()).thenReturn(true);
        var first = knockback(); listener.knockback(first);
        var velocity = new PlayerVelocityEvent(victim, new Vector(0.1, 0.2, 0));
        classic.beginVelocity(velocity); classic.velocity(velocity);
        assertEquals(new Vector(0.1, 0.2, 0), velocity.getVelocity());
    }
    @Test public void classicFinalVelocitySurvivesSyncButModernDoesNotUseClassicProtection() {
        classic.acceptedDamage(damage()); var knockback = knockback(); listener.knockback(knockback); classic.finishedKnockback(knockback);
        // Native knockback applies the event delta before PlayerVelocityEvent is dispatched.
        when(victim.getVelocity()).thenReturn(new Vector(0.4, 0.36075, 0));
        var event = new PlayerVelocityEvent(victim, new Vector()); classic.beginVelocity(event);
        event.setVelocity(new Vector(0.4, 7, 0)); classic.velocity(event);
        assertEquals(new Vector(0.4, 0.36075, 0), event.getVelocity());
        settings = settings.withMode(CombatMode.MODERN, ClassicSettings.defaults());
        var modern = new PlayerVelocityEvent(victim, new Vector(0.3, 0.4, 0));
        classic.beginVelocity(modern); classic.velocity(modern); assertEquals(new Vector(0.3, 0.4, 0), modern.getVelocity());
    }
    @Test public void modeSwitchInsideApiCallbackCannotCommitOldModeVector() {
        doAnswer(call -> { if (call.getArgument(0) instanceof KnockbackModifyEvent)
            settings = settings.withMode(CombatMode.MODERN, ClassicSettings.defaults()); return null;
        }).when(manager).callEvent(any());
        var event = knockback(); listener.knockback(event);
        assertEquals(new Vector(0.4, 0.4, 0), event.getKnockback()); assertEquals(0, classic.applied());
    }
    @Test public void crossRegionVictimIsNeverReadOrModified() {
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(victim)).thenReturn(false);
        clearInvocations(victim); classic.reconcile(victim); listener.knockback(knockback());
        verify(victim, never()).getVelocity(); verify(victim, never()).getPersistentDataContainer();
    }
    @Test public void rodPacketProtectionUsesCurrentVectorNotFrozenCollisionVector() {
        classic.applied(victim, new Vector(0.4, 0.36075, 0), settings,
                KnockbackMathTest.hit(HitType.FISHING_ROD, true, false, false, 1, false));
        when(victim.getTicksLived()).thenReturn(101);
        when(victim.getVelocity()).thenReturn(new Vector(0.35, 0.27, 0));
        var event = new PlayerVelocityEvent(victim, new Vector(0.35, 0.27, 0)); classic.beginVelocity(event);
        event.setVelocity(new Vector(0.35, 8, 0)); classic.velocity(event);
        assertEquals(new Vector(0.35, 0.27, 0), event.getVelocity());
    }
    @Test public void lateCancellationOfNativeKnockbackDoesNotConsumeTheReplacement() {
        classic.acceptedDamage(damage());
        var cancelled = knockback(); listener.knockback(cancelled); cancelled.setCancelled(true); classic.finishedKnockback(cancelled);
        assertEquals(0, classic.applied());
        var nextComponent = knockback(); listener.knockback(nextComponent); classic.finishedKnockback(nextComponent);
        assertFalse(nextComponent.isCancelled()); assertEquals(1, classic.applied());
    }
    @Test public void damageRejectedByAnotherPluginDoesNotCreateAnEarlyHitWindow() {
        var cancelled = damage(); classic.acceptedDamage(cancelled); when(cancelled.isCancelled()).thenReturn(true);
        var event = new PrePlayerAttackEntityEvent(attacker, victim, true); classic.beforeAttack(event);
        assertFalse(event.isCancelled());
    }
    @Test public void largerDamageCannotBypassClassicHitInterval() {
        classic.acceptedDamage(damage());
        when(victim.getTicksLived()).thenReturn(101);
        var stronger = damage(); when(stronger.getFinalDamage()).thenReturn(10d);
        classic.guardDamage(stronger); verify(stronger).setCancelled(true);
    }
    private FishHook rodHook() { return rodHook(attacker); }
    private FishHook rodHook(Player shooter) {
        FishHook hook = mock(FishHook.class); when(hook.getVelocity()).thenReturn(new Vector(1, 0, 0));
        HookTestState.install(hook);
        when(hook.getShooter()).thenReturn(shooter); when(hook.isValid()).thenReturn(true);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(hook)).thenReturn(true);
        when(rodSource.getDirectEntity()).thenReturn(hook); when(rodSource.getCausingEntity()).thenReturn(shooter);
        when(tracker.context(eq(victim), any(), anyString(), anyBoolean()))
                .thenReturn(KnockbackMathTest.hit(HitType.FISHING_ROD, true, false, false, 1, false));
        doAnswer(call -> { ((Runnable)call.getArgument(4)).run(); return null; }).when(tracker).withSource(eq(shooter), eq(victim), eq(hook), eq(HitType.FISHING_ROD), any());
        doAnswer(call -> {
            var event = new EntityKnockbackEvent(victim, EntityKnockbackEvent.Cause.UNKNOWN, new Vector(0.4, 0.4, 0));
            classic.nativeRodDamageKnockback(event);
            if (!event.isCancelled()) { listener.knockback(event); classic.finishedKnockback(event); }
            return null;
        }).when(victim).knockback(anyDouble(), anyDouble(), anyDouble());
        return hook;
    }
    private void rodDamage(FishHook hook, java.util.function.Consumer<EntityDamageByEntityEvent> laterListener) {
        doAnswer(call -> {
            DamageSource source = call.getArgument(1);
            assertSame(rodSource, source);
            var event = new EntityDamageByEntityEvent(hook, victim, EntityDamageEvent.DamageCause.PROJECTILE, source, 0);
            classic.guardDamage(event);
            if (!event.isCancelled()) classic.acceptedDamage(event);
            laterListener.accept(event);
            if (!event.isCancelled()) when(victim.getLastDamageCause()).thenReturn(event);
            return null;
        }).when(victim).damage(eq(0d), any(DamageSource.class));
    }
    @Test public void cancelledRodProfileStillCompletesOneNativeHitAndProtectsTheWindow() { nativeRodFallback("selection", false); }
    @Test public void cancelledRodModificationStillCompletesOneNativeHitAndProtectsTheWindow() { nativeRodFallback("modification", false); }
    @Test public void missingRodProfileStillCompletesOneNativeHitAndProtectsTheWindow() { nativeRodFallback("missing", false); }
    @Test public void disabledSelectedRodProfileStillCompletesOneNativeHitAndProtectsTheWindow() { nativeRodFallback("disabled", false); }
    @Test public void nestedKnockbackDuringRodProfileFallbackCannotApplyTwoNativeImpulses() { nativeRodFallback("nested", false); }
    @Test public void cancelledNativeFallbackNeverSendsFeedbackOrCreatesAWindow() { nativeRodFallback("selection", true); }
    private void nativeRodFallback(String policy, boolean cancelNative) {
        var profiles = new HashMap<>(settings.profiles());
        profiles.put("disabled", KnockbackMathTest.profile("enabled", false));
        settings = new Settings(true, false, true, true, Set.of(), profiles, Map.of(), Map.of())
                .withMode(CombatMode.CLASSIC, ClassicSettings.defaults());
        RodVelocity.Transport transport = mock(RodVelocity.Transport.class);
        classic = new ClassicCombat(plugin, () -> settings, store, tracker, RodVelocity.immediate(transport));
        listener = new KnockbackListener(() -> settings, store, tracker, () -> true, classic);
        World world = mock(World.class);
        when(victim.getWorld()).thenReturn(world); when(victim.getHurtSound()).thenReturn(Sound.ENTITY_PLAYER_HURT);
        FishHook hook = rodHook(); rodDamage(hook, event -> {});
        Vector before = new Vector(0.12, -0.08, 0.05), nativeImpulse = new Vector(0.4, 0.4, 0);
        AtomicReference<Vector> motion = new AtomicReference<>(before.clone());
        AtomicInteger nativeApplications = new AtomicInteger();
        when(victim.getVelocity()).thenAnswer(call -> motion.get().clone());
        doAnswer(call -> {
            var event = new EntityKnockbackEvent(victim, EntityKnockbackEvent.Cause.UNKNOWN, nativeImpulse.clone());
            classic.nativeRodDamageKnockback(event);
            if (!event.isCancelled()) listener.knockback(event);
            if (cancelNative) event.setCancelled(true);
            classic.finishedKnockback(event);
            // Real native knockback applies the final event even when MKB declines to modify it.
            if (!event.isCancelled()) {
                motion.set(motion.get().clone().add(event.getKnockback()));
                nativeApplications.incrementAndGet();
            }
            return null;
        }).when(victim).knockback(anyDouble(), anyDouble(), anyDouble());
        doAnswer(call -> {
            Object event = call.getArgument(0);
            if (event instanceof KnockbackProfileEvent profile) {
                if (policy.equals("selection") || policy.equals("nested")) profile.setCancelled(true);
                if (policy.equals("nested")) victim.knockback(0.4, -1, 0);
                if (policy.equals("missing") || policy.equals("disabled")) profile.setProfile(policy);
            }
            if (event instanceof KnockbackModifyEvent modify && policy.equals("modification")) modify.setCancelled(true);
            if (event instanceof PlayerVelocityEvent velocity) {
                classic.beginVelocity(velocity); classic.velocity(velocity);
            }
            return null;
        }).when(manager).callEvent(any());
        ClassicCombat.RodResult result = classic.rodImpactResult(hook, attacker, victim, 0.4);
        assertEquals(cancelNative ? before : before.clone().add(nativeImpulse), motion.get());
        assertEquals(cancelNative ? 0 : 1, nativeApplications.get());
        assertEquals(cancelNative ? ClassicCombat.RodResult.REJECTED : ClassicCombat.RodResult.APPLIED, result);
        assertEquals("Native fallback must not count as a custom vector modification", 0, classic.applied());
        var sword = new PrePlayerAttackEntityEvent(attacker, victim, true); classic.beforeAttack(sword);
        assertEquals(!cancelNative, sword.isCancelled());
        if (cancelNative) {
            verify(transport, never()).send(any(), any());
            verify(victim, never()).playHurtAnimation(anyFloat()); verifyNoInteractions(world);
            return;
        }
        var order = inOrder(transport, victim, world);
        order.verify(transport).send(victim, before.clone().add(nativeImpulse));
        order.verify(victim).playHurtAnimation(-90f);
        order.verify(world).playSound(victim, Sound.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 1, 1);
        when(victim.getNoDamageTicks()).thenReturn(0); // Even an external immunity reset cannot bypass the shared stamp.
        Player other = player(-1);
        for (int tick = 100; tick < 110; tick++) {
            when(victim.getTicksLived()).thenReturn(tick);
            Player shooter = tick % 2 == 0 ? attacker : other;
            assertEquals(ClassicCombat.RodResult.REJECTED, classic.rodImpactResult(rodHook(shooter), shooter, victim, 0.4));
        }
        verify(victim, times(1)).damage(0, rodSource);
        verify(victim, times(policy.equals("nested") ? 2 : 1)).knockback(anyDouble(), anyDouble(), anyDouble());
        verify(transport, times(1)).send(any(), any());
        verify(victim, times(1)).playHurtAnimation(-90f);
        verify(world, times(1)).playSound(victim, Sound.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 1, 1);
        verify(victim, never()).setHealth(anyDouble());
        when(victim.getTicksLived()).thenReturn(110);
        var allowed = new PrePlayerAttackEntityEvent(other, victim, true); classic.beforeAttack(allowed);
        assertFalse(allowed.isCancelled());
    }
    @Test public void twoAnglersShareOneHurtSoundAnimationAndVelocityUntilTheTenTickBoundary() {
        RodVelocity.Transport transport = mock(RodVelocity.Transport.class);
        classic = new ClassicCombat(plugin, () -> settings, store, tracker, RodVelocity.immediate(transport));
        listener = new KnockbackListener(() -> settings, store, tracker, () -> true, classic);
        World world = mock(World.class);
        when(victim.getWorld()).thenReturn(world); when(victim.getHurtSound()).thenReturn(Sound.ENTITY_PLAYER_HURT);
        Player other = player(-1);
        AtomicInteger remaining = new AtomicInteger();
        when(victim.getNoDamageTicks()).thenAnswer(call -> remaining.get());
        doAnswer(call -> { remaining.set(call.getArgument(0)); return null; }).when(victim).setNoDamageTicks(anyInt());
        FishHook first = rodHook(); rodDamage(first, event -> {});
        assertEquals(ClassicCombat.RodResult.APPLIED, classic.rodImpactResult(first, attacker, victim, 0.4));
        assertEquals(20, remaining.get());
        for (int tick = 101; tick < 110; tick++) {
            when(victim.getTicksLived()).thenReturn(tick); remaining.decrementAndGet();
            Player shooter = tick % 2 == 0 ? attacker : other;
            FishHook next = rodHook(shooter);
            assertEquals(ClassicCombat.RodResult.REJECTED, classic.rodImpactResult(next, shooter, victim, 0.4));
            assertEquals("Rejected rods must not refresh immunity", 120 - tick, remaining.get());
        }
        verify(victim, times(1)).damage(0, rodSource);
        verify(victim, times(1)).playHurtAnimation(-90f); verify(transport, times(1)).send(any(), any());
        verify(world, times(1)).playSound(victim, Sound.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 1, 1);
        when(victim.getTicksLived()).thenReturn(110); remaining.decrementAndGet();
        FishHook boundary = rodHook(other); rodDamage(boundary, event -> {});
        assertEquals(ClassicCombat.RodResult.APPLIED, classic.rodImpactResult(boundary, other, victim, 0.4));
        assertEquals(20, remaining.get());
        verify(victim, times(2)).damage(0, rodSource); verify(victim, times(2)).knockback(anyDouble(), anyDouble(), anyDouble());
        verify(victim, times(2)).playHurtAnimation(-90f); verify(transport, times(2)).send(any(), any());
        verify(world, times(2)).playSound(victim, Sound.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 1, 1);
        verify(victim, never()).setNoDamageTicks(0); verify(victim, never()).setHealth(anyDouble());
    }
    @Test public void successfulRodBlocksSwordThroughoutRodWindowAndAllowsTenTickBoundary() {
        FishHook hook = rodHook(); rodDamage(hook, event -> {});
        assertEquals(ClassicCombat.RodResult.APPLIED, classic.rodImpactResult(hook, attacker, victim, 0.4));
        for (int tick = 100; tick < 110; tick++) {
            when(victim.getTicksLived()).thenReturn(tick);
            var attack = new PrePlayerAttackEntityEvent(attacker, victim, true); classic.beforeAttack(attack);
            assertTrue("Rod -> sword must not stack at tick " + tick, attack.isCancelled());
        }
        when(victim.getTicksLived()).thenReturn(110);
        var allowed = new PrePlayerAttackEntityEvent(attacker, victim, true); classic.beforeAttack(allowed);
        assertFalse(allowed.isCancelled());
        verify(attacker, never()).resetCooldown();
        verify(victim, times(1)).damage(0, rodSource);
        verify(victim, times(1)).knockback(anyDouble(), anyDouble(), anyDouble());
    }
    @Test public void rodFollowupDamageAndKnockbackCannotBypassPreAttackProtectionForPlayersOrBots() {
        FishHook hook = rodHook(); rodDamage(hook, event -> {});
        assertEquals(ClassicCombat.RodResult.APPLIED, classic.rodImpactResult(hook, attacker, victim, 0.4));
        when(tracker.context(eq(victim), any(), anyString(), anyBoolean()))
                .thenReturn(KnockbackMathTest.hit(HitType.MELEE, true, false, false, 1, false));
        for (CombatantKind kind : new CombatantKind[]{CombatantKind.PLAYER, CombatantKind.BOT}) {
            when(tracker.kind(victim)).thenReturn(kind);
            var attack = damage(); classic.guardDamage(attack); verify(attack).setCancelled(true);
            var component = knockback(); listener.knockback(component);
            assertTrue(component.isCancelled());
        }
        when(victim.getTicksLived()).thenReturn(110);
        var allowed = damage(); classic.guardDamage(allowed); verify(allowed, never()).setCancelled(true);
        assertEquals(1, classic.applied());
    }
    @Test public void rodGuardStartsBeforeKnockbackReturnsSoNestedLeftClickCannotStack() {
        FishHook hook = rodHook();
        AtomicBoolean blocked = new AtomicBoolean();
        rodDamage(hook, event -> {
            var nested = new PrePlayerAttackEntityEvent(attacker, victim, true);
            classic.beforeAttack(nested);
            blocked.set(nested.isCancelled());
        });
        assertEquals(ClassicCombat.RodResult.APPLIED, classic.rodImpactResult(hook, attacker, victim, 0.4));
        assertTrue(blocked.get());
    }
    @Test public void rodThenSwordSequenceHasOneDamagePerAcceptedSwordAndOneVectorPerHit() {
        FishHook hook = rodHook(); rodDamage(hook, event -> {});
        AtomicInteger remaining = new AtomicInteger();
        when(victim.getNoDamageTicks()).thenAnswer(call -> remaining.get());
        doAnswer(call -> { remaining.set(call.getArgument(0)); return null; }).when(victim).setNoDamageTicks(anyInt());
        assertEquals(ClassicCombat.RodResult.APPLIED, classic.rodImpactResult(hook, attacker, victim, 0.4));
        when(victim.getVelocity()).thenReturn(new Vector(0.4, 0.36075, 0));
        when(tracker.context(eq(victim), any(), anyString(), anyBoolean()))
                .thenReturn(KnockbackMathTest.hit(HitType.MELEE, true, false, false, 1, false));
        DamageSource source = mock(DamageSource.class); when(source.getDirectEntity()).thenReturn(attacker);
        int previousTick = 100, damageCount = 0, swordVectors = 0;
        double health = 20;
        for (int tick : new int[]{100, 109, 110, 110, 119, 120}) {
            remaining.set(Math.max(0, remaining.get() - (tick - previousTick))); previousTick = tick;
            when(victim.getTicksLived()).thenReturn(tick);
            var attempt = new PrePlayerAttackEntityEvent(attacker, victim, true); classic.beforeAttack(attempt);
            if (attempt.isCancelled()) continue;
            // The native damage path must enter the normal branch, not the larger-damage difference branch.
            assertTrue(remaining.get() <= victim.getMaximumNoDamageTicks() / 2);
            var damage = new EntityDamageByEntityEvent(attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 3);
            classic.guardDamage(damage); assertFalse(damage.isCancelled());
            classic.acceptedDamage(damage); when(victim.getLastDamageCause()).thenReturn(damage);
            remaining.set(victim.getMaximumNoDamageTicks());
            health -= damage.getFinalDamage(); damageCount++;
            Vector before = victim.getVelocity().clone();
            var base = knockback(); listener.knockback(base); classic.finishedKnockback(base);
            assertFalse(base.isCancelled()); swordVectors++;
            when(victim.getVelocity()).thenReturn(before.clone().add(base.getKnockback()));
            var sprintComponent = knockback(); listener.knockback(sprintComponent);
            assertTrue(sprintComponent.isCancelled());
            var packet = new PlayerVelocityEvent(victim, before); classic.beginVelocity(packet);
            packet.setVelocity(new Vector(1, 8, 0)); classic.velocity(packet);
            assertEquals(new Vector(0.4, 0.36075, 0), packet.getVelocity());
        }
        assertEquals(2, damageCount); assertEquals(14, health, 0); assertEquals(2, swordVectors);
        assertEquals(3, classic.applied()); assertEquals(4, classic.preventedHits());
        verify(victim, times(1)).damage(0, rodSource); verify(victim, never()).setNoDamageTicks(0);
        verify(attacker, never()).resetCooldown();
    }
    @Test public void rodFollowupIntervalIsConfigurableWithoutChangingRodRepeatInterval() {
        for (int interval : new int[]{1, 4, 14}) {
            settings = settings.withMode(CombatMode.CLASSIC, new ClassicSettings(10, 4, true, true, false, true, 2, interval));
            when(victim.getTicksLived()).thenReturn(100);
            FishHook hook = rodHook(); rodDamage(hook, event -> {});
            assertEquals(ClassicCombat.RodResult.APPLIED, classic.rodImpactResult(hook, attacker, victim, 0.4));
            int effective = Math.max(10, interval);
            when(victim.getTicksLived()).thenReturn(100 + effective - 1);
            var early = new PrePlayerAttackEntityEvent(attacker, victim, true); classic.beforeAttack(early);
            assertTrue(early.isCancelled());
            when(victim.getTicksLived()).thenReturn(100 + effective);
            var boundary = new PrePlayerAttackEntityEvent(attacker, victim, true); classic.beforeAttack(boundary);
            assertFalse(boundary.isCancelled());
            verify(victim, atLeastOnce()).setNoDamageTicks(20);
        }
    }
    @Test public void rodFollowupDoesNotAddAMeleeWindowAfterSwitchingToModern() {
        FishHook hook = rodHook(); rodDamage(hook, event -> {});
        assertEquals(ClassicCombat.RodResult.APPLIED, classic.rodImpactResult(hook, attacker, victim, 0.4));
        settings = settings.withMode(CombatMode.MODERN, ClassicSettings.defaults());
        var sword = new PrePlayerAttackEntityEvent(attacker, victim, true); classic.beforeAttack(sword);
        assertFalse(sword.isCancelled());
        var damage = damage(); classic.guardDamage(damage); verify(damage, never()).setCancelled(true);
    }
    @Test public void previousRodVelocityEventCannotOverwriteOrConsumeNewMeleeProtection() {
        classic.applied(victim, new Vector(0.5, 0.34, 0), settings,
                KnockbackMathTest.hit(HitType.FISHING_ROD, true, false, false, 1, false));
        var rodPacket = new PlayerVelocityEvent(victim, new Vector()); classic.beginVelocity(rodPacket);
        classic.acceptedDamage(damage());
        var sword = knockback(); listener.knockback(sword); classic.finishedKnockback(sword);
        when(victim.getVelocity()).thenReturn(new Vector(0.4, 0.36075, 0));
        rodPacket.setVelocity(new Vector(0.2, 0.25, 0)); classic.velocity(rodPacket);
        assertEquals(new Vector(0.2, 0.25, 0), rodPacket.getVelocity());
        var swordPacket = new PlayerVelocityEvent(victim, new Vector()); classic.beginVelocity(swordPacket);
        swordPacket.setVelocity(new Vector(0.4, 8, 0)); classic.velocity(swordPacket);
        assertEquals(new Vector(0.4, 0.36075, 0), swordPacket.getVelocity());
    }
    @Test public void cancelledRodKnockbackIsNotRetriedOrAppliedThroughAnotherPath() {
        FishHook hook = rodHook(); rodDamage(hook, event -> {});
        doAnswer(call -> {
            var event = new EntityKnockbackEvent(victim, EntityKnockbackEvent.Cause.UNKNOWN, new Vector(0.4, 0.4, 0));
            listener.knockback(event);
            event.setCancelled(true); classic.finishedKnockback(event);
            return null;
        }).when(victim).knockback(anyDouble(), anyDouble(), anyDouble());
        assertEquals(ClassicCombat.RodResult.REJECTED, classic.rodImpactResult(hook, attacker, victim, 0.4));
        verify(victim, times(1)).knockback(anyDouble(), anyDouble(), anyDouble());
        verify(victim, never()).setVelocity(any()); assertEquals(0, classic.applied());
        verify(victim, never()).playHurtAnimation(anyFloat());
        verify(hook).setHookedEntity(null); verify(hook, never()).remove();
        var sword = new PrePlayerAttackEntityEvent(attacker, victim, true); classic.beforeAttack(sword);
        assertFalse(sword.isCancelled());
    }
    @Test public void rodMakesRealZeroDamageHitWithHurtAnimationAndShortSwordProtection() {
        FishHook hook = rodHook(); rodDamage(hook, event -> {});
        classic.rodImpact(hook, attacker, victim, 0.4); classic.rodImpact(hook, attacker, victim, 0.4);
        verify(victim, times(1)).damage(0, rodSource);
        verify(rodSourceBuilder).withDirectEntity(hook); verify(rodSourceBuilder).withCausingEntity(attacker);
        verify(victim, times(1)).knockback(anyDouble(), anyDouble(), anyDouble());
        var order = inOrder(victim, hook);
        order.verify(victim).knockback(anyDouble(), anyDouble(), anyDouble());
        order.verify(hook).setHookedEntity(null); order.verify(hook).setVelocity(new Vector(-0.1, -0.05, 0));
        order.verify(victim).setVelocity(any());
        order.verify(victim).playHurtAnimation(-90f);
        verify(victim, never()).hideEntity(any(), any()); verify(attacker, never()).hideEntity(any(), any());
        verify(hook, never()).setHookedEntity(victim);
        verify(victim).setNoDamageTicks(20); verify(victim).setLastDamage(0);
        verify(victim, never()).setNoDamageTicks(0); verify(victim, never()).setHealth(anyDouble());
        var nextMelee = new PrePlayerAttackEntityEvent(attacker, victim, true); classic.beforeAttack(nextMelee);
        assertTrue(nextMelee.isCancelled()); assertEquals(1, classic.applied());
    }
    @Test public void cancellationAfterKnockbackMonitorStillRejectsVelocityNotificationAndProtection() {
        FishHook hook = rodHook(); rodDamage(hook, event -> {});
        doAnswer(call -> {
            var event = new EntityKnockbackEvent(victim, EntityKnockbackEvent.Cause.UNKNOWN, new Vector(0.4, 0.4, 0));
            listener.knockback(event); classic.finishedKnockback(event);
            event.setCancelled(true); // A later MONITOR handler ran before native velocity application.
            return null;
        }).when(victim).knockback(anyDouble(), anyDouble(), anyDouble());
        assertEquals(ClassicCombat.RodResult.REJECTED, classic.rodImpactResult(hook, attacker, victim, 0.4));
        var velocity = new PlayerVelocityEvent(victim, new Vector(0.1, 0.2, 0));
        classic.beginVelocity(velocity); velocity.setVelocity(new Vector(0.1, 8, 0)); classic.velocity(velocity);
        assertEquals(new Vector(0.1, 8, 0), velocity.getVelocity());
        var sword = new PrePlayerAttackEntityEvent(attacker, victim, true); classic.beforeAttack(sword);
        assertFalse(sword.isCancelled());
    }
    @Test public void oldFourTickSettingCannotStackRodsOrExtendTheTenTickWindow() {
        settings = settings.withMode(CombatMode.CLASSIC, new ClassicSettings(10, 4, true, true, false, true, 2, 4));
        FishHook hook = rodHook(); rodDamage(hook, event -> {});
        AtomicInteger remaining = new AtomicInteger();
        when(victim.getNoDamageTicks()).thenAnswer(call -> remaining.get());
        doAnswer(call -> { remaining.set(call.getArgument(0)); return null; }).when(victim).setNoDamageTicks(anyInt());
        assertEquals(ClassicCombat.RodResult.APPLIED, classic.rodImpactResult(hook, attacker, victim, 0.4));
        assertEquals(20, remaining.get());
        for (int tick = 101; tick < 110; tick++) {
            when(victim.getTicksLived()).thenReturn(tick); remaining.decrementAndGet();
            FishHook recast = rodHook();
            assertEquals(ClassicCombat.RodResult.REJECTED, classic.rodImpactResult(recast, attacker, victim, 0.4));
            assertEquals(120 - tick, remaining.get());
        }
        when(victim.getTicksLived()).thenReturn(110); remaining.decrementAndGet();
        FishHook boundary = rodHook(); rodDamage(boundary, event -> {});
        assertEquals(ClassicCombat.RodResult.APPLIED, classic.rodImpactResult(boundary, attacker, victim, 0.4));
        verify(victim, times(2)).damage(0, rodSource);
        verify(victim, times(2)).knockback(anyDouble(), anyDouble(), anyDouble());
        verify(victim, never()).setNoDamageTicks(0);
        assertEquals(2, classic.applied());
    }
    @Test public void repeatedCastsStayBlockedEvenIfAnotherPluginClearsNativeImmunity() {
        FishHook first = rodHook(); rodDamage(first, event -> {});
        assertEquals(ClassicCombat.RodResult.APPLIED, classic.rodImpactResult(first, attacker, victim, 0.4));
        when(victim.getNoDamageTicks()).thenReturn(0);
        for (int tick : new int[]{102, 104, 106, 108, 109}) {
            when(victim.getTicksLived()).thenReturn(tick);
            FishHook recast = rodHook();
            assertEquals(ClassicCombat.RodResult.REJECTED, classic.rodImpactResult(recast, attacker, victim, 0.4));
            verify(recast, never()).remove();
        }
        verify(victim, times(1)).damage(0, rodSource);
        verify(victim, times(1)).knockback(anyDouble(), anyDouble(), anyDouble());
        verify(victim, times(1)).playHurtAnimation(anyFloat());
    }
    @Test public void nestedRodAndSwordBeforeDamageMonitorCannotStack() {
        FishHook hook = rodHook();
        FishHook nested = mock(FishHook.class);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(nested)).thenReturn(true);
        doAnswer(call -> {
            assertEquals(ClassicCombat.RodResult.REJECTED, classic.rodImpactResult(nested, attacker, victim, 0.4));
            var sword = new PrePlayerAttackEntityEvent(attacker, victim, true);
            classic.beforeAttack(sword); assertTrue(sword.isCancelled());
            var event = new EntityDamageByEntityEvent(hook, victim, EntityDamageEvent.DamageCause.PROJECTILE, rodSource, 0);
            classic.guardDamage(event); assertFalse(event.isCancelled());
            classic.acceptedDamage(event); when(victim.getLastDamageCause()).thenReturn(event);
            return null;
        }).when(victim).damage(eq(0d), any(DamageSource.class));
        assertEquals(ClassicCombat.RodResult.APPLIED, classic.rodImpactResult(hook, attacker, victim, 0.4));
        verify(victim, times(1)).damage(0, rodSource);
        verify(victim, times(1)).knockback(anyDouble(), anyDouble(), anyDouble());
        when(victim.getTicksLived()).thenReturn(110);
        var sword = new PrePlayerAttackEntityEvent(attacker, victim, true);
        classic.beforeAttack(sword); assertFalse(sword.isCancelled());
    }
    @Test public void positiveDamageAddedByAnotherPluginKeepsItsFullDamageWindow() {
        FishHook hook = rodHook(); rodDamage(hook, event -> {
            event.setDamage(2);
            when(victim.getLastDamage()).thenReturn(2d);
            when(victim.getNoDamageTicks()).thenReturn(20);
        });
        classic.rodImpact(hook, attacker, victim, 0.4);
        verify(victim, never()).setNoDamageTicks(anyInt());
        verify(victim, never()).setLastDamage(anyDouble());
        verify(victim, never()).playHurtAnimation(anyFloat());
        when(victim.getTicksLived()).thenReturn(102);
        var early = new PrePlayerAttackEntityEvent(attacker, victim, true); classic.beforeAttack(early);
        assertTrue(early.isCancelled());
        when(victim.getTicksLived()).thenReturn(110);
        var allowed = new PrePlayerAttackEntityEvent(attacker, victim, true); classic.beforeAttack(allowed);
        assertFalse(allowed.isCancelled());
    }
    @Test public void protectionInstalledDuringZeroDamageEventIsNotShortened() {
        FishHook hook = rodHook(); rodDamage(hook, event -> when(victim.getNoDamageTicks()).thenReturn(35));
        classic.rodImpact(hook, attacker, victim, 0.4);
        verify(victim, never()).setNoDamageTicks(anyInt());
        verify(victim, never()).setLastDamage(anyDouble());
    }
    @Test public void rodIntervalLongerThanMeleeDoesNotCreateAnExtendedNativeImmunity() {
        settings = settings.withMode(CombatMode.CLASSIC, new ClassicSettings(1, 4, true, true));
        FishHook hook = rodHook(); rodDamage(hook, event -> {});
        classic.rodImpact(hook, attacker, victim, 0.4);
        verify(victim).setNoDamageTicks(2);
        when(victim.getTicksLived()).thenReturn(101);
        assertEquals(ClassicCombat.RodResult.REJECTED, classic.rodImpactResult(hook, attacker, victim, 0.4));
        verify(victim, times(1)).damage(0, rodSource);
    }
    @Test public void impactStrengthPointFourUsesFullConfiguredRodVectorAndPointTwoUsesHalf() {
        settings = new Settings(true, false, true, true, Set.of(), Map.of("default", KnockbackMathTest.profile(
                "classic.horizontal-ground", 0.5274, "classic.horizontal-air", 0.5274,
                "classic.vertical-ground", 0.361375, "classic.vertical-air", 0.361375)), Map.of(), Map.of())
                .withMode(CombatMode.CLASSIC, ClassicSettings.defaults());
        FishHook hook = rodHook(); rodDamage(hook, event -> {});
        var results = new ArrayList<Vector>();
        org.mockito.stubbing.Answer<Object> collect = call -> {
            var event = new EntityKnockbackEvent(victim, EntityKnockbackEvent.Cause.UNKNOWN, new Vector(0.2, 0.2, 0));
            listener.knockback(event); classic.finishedKnockback(event); results.add(event.getKnockback());
            return null;
        };
        doAnswer(collect).when(victim).knockback(anyDouble(), anyDouble(), anyDouble());
        classic.rodImpact(hook, attacker, victim, 0.4);
        FishHook recast = rodHook(); rodDamage(recast, event -> {});
        doAnswer(collect).when(victim).knockback(anyDouble(), anyDouble(), anyDouble());
        when(victim.getTicksLived()).thenReturn(110); classic.rodImpact(recast, attacker, victim, 0.2);
        assertEquals(new Vector(0.5274, 0.361375, 0), results.get(0));
        assertEquals(new Vector(0.2637, 0.1806875, 0), results.get(1));
    }
    @Test public void retainedHookCannotHitAgainAfterTheVictimImmunityExpires() {
        FishHook hook = rodHook(); rodDamage(hook, event -> {});
        assertEquals(ClassicCombat.RodResult.APPLIED, classic.rodImpactResult(hook, attacker, victim, 0.4));
        when(victim.getTicksLived()).thenReturn(125);
        when(victim.getNoDamageTicks()).thenReturn(0);
        assertEquals(ClassicCombat.RodResult.REJECTED, classic.rodImpactResult(hook, attacker, victim, 0.4));
        verify(victim, times(1)).damage(0, rodSource);
        verify(victim, times(1)).knockback(anyDouble(), anyDouble(), anyDouble());
        verify(hook, never()).setHookedEntity(victim);
        verify(victim, never()).hideEntity(any(), any());
        verify(hook, times(1)).setVelocity(new Vector(-0.1, -0.05, 0));
        verify(hook, never()).remove();
    }
    @Test public void nativeNonPlayerZeroDamageWindowAlsoUsesRodTiming() {
        settings = new Settings(true, true, true, true, Set.of(), settings.profiles(), Map.of(), Map.of())
                .withMode(CombatMode.CLASSIC, ClassicSettings.defaults());
        LivingEntity mob = mock(LivingEntity.class);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(mob)).thenReturn(true);
        when(mob.getUniqueId()).thenReturn(UUID.randomUUID());
        when(mob.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        when(mob.getMaximumNoDamageTicks()).thenReturn(20); when(mob.getTicksLived()).thenReturn(100);
        when(mob.getVelocity()).thenReturn(new Vector());
        when(mob.getLocation()).thenReturn(new Location(null, 1, 64, 0));
        AtomicInteger remaining = new AtomicInteger();
        when(mob.getNoDamageTicks()).thenAnswer(call -> remaining.get());
        doAnswer(call -> { remaining.set(call.getArgument(0)); return null; }).when(mob).setNoDamageTicks(anyInt());
        when(tracker.kind(mob)).thenReturn(CombatantKind.OTHER);
        FishHook hook = rodHook();
        doAnswer(call -> { ((Runnable)call.getArgument(4)).run(); return null; })
                .when(tracker).withSource(eq(attacker), eq(mob), eq(hook), eq(HitType.FISHING_ROD), any());
        doAnswer(call -> {
            var event = new EntityDamageByEntityEvent(hook, mob, EntityDamageEvent.DamageCause.PROJECTILE, rodSource, 0);
            classic.acceptedDamage(event); when(mob.getLastDamageCause()).thenReturn(event);
            remaining.set(20); // Native non-player damage(0) established ordinary immunity.
            return null;
        }).when(mob).damage(eq(0d), any(DamageSource.class));
        when(tracker.context(eq(mob), any(), anyString(), anyBoolean()))
                .thenReturn(KnockbackMathTest.hit(HitType.FISHING_ROD, true, false, false, 1, false));
        doAnswer(call -> {
            var event = new EntityKnockbackEvent(mob, EntityKnockbackEvent.Cause.UNKNOWN, new Vector(0.4, 0.4, 0));
            listener.knockback(event); classic.finishedKnockback(event);
            return null;
        }).when(mob).knockback(anyDouble(), anyDouble(), anyDouble());
        assertEquals(ClassicCombat.RodResult.APPLIED, classic.rodImpactResult(hook, attacker, mob, 0.4));
        assertEquals(20, remaining.get()); verify(mob, never()).setNoDamageTicks(0);
    }
    @Test public void immediateRodSendsAfterReboundBeforeHurtAndProtectsVelocityOnce() {
        RodVelocity.Transport transport = mock(RodVelocity.Transport.class);
        classic = new ClassicCombat(plugin, () -> settings, store, tracker, RodVelocity.immediate(transport));
        listener = new KnockbackListener(() -> settings, store, tracker, () -> true, classic);
        FishHook hook = rodHook(); rodDamage(hook, event -> {});
        Vector actual = new Vector(0.5, 0.34, 0);
        doAnswer(call -> {
            var event = new EntityKnockbackEvent(victim, EntityKnockbackEvent.Cause.UNKNOWN, actual);
            listener.knockback(event); classic.finishedKnockback(event);
            when(victim.getVelocity()).thenReturn(actual);
            return null;
        }).when(victim).knockback(anyDouble(), anyDouble(), anyDouble());
        doAnswer(call -> {
            PlayerVelocityEvent event = call.getArgument(0);
            classic.beginVelocity(event);
            event.setVelocity(new Vector(0, 8, 0)); // a velocity synchronizer attempted to replace Y
            classic.velocity(event);
            return null;
        }).when(manager).callEvent(isA(PlayerVelocityEvent.class));
        assertEquals(ClassicCombat.RodResult.APPLIED, classic.rodImpactResult(hook, attacker, victim, 0.4));
        var order = inOrder(hook, transport, victim);
        order.verify(hook).setVelocity(new Vector(-0.1, -0.05, 0));
        order.verify(transport).send(victim, actual);
        order.verify(victim).playHurtAnimation(-90f);
        verify(victim, never()).hideEntity(any(), any());
        verify(hook, never()).setHookedEntity(victim);
        verify(manager, times(1)).callEvent(isA(PlayerVelocityEvent.class));
        var later = new PlayerVelocityEvent(victim, new Vector(0, 1, 0));
        classic.beginVelocity(later); classic.velocity(later);
        assertEquals(new Vector(0, 1, 0), later.getVelocity());
    }
    @Test public void cancelledImmediateVelocityRemovesOnlyItsRodImmunityAndSwordLock() {
        RodVelocity.Transport transport = mock(RodVelocity.Transport.class);
        classic = new ClassicCombat(plugin, () -> settings, store, tracker, RodVelocity.immediate(transport));
        listener = new KnockbackListener(() -> settings, store, tracker, () -> true, classic);
        FishHook hook = rodHook(); rodDamage(hook, event -> {});
        AtomicInteger remaining = new AtomicInteger();
        when(victim.getNoDamageTicks()).thenAnswer(call -> remaining.get());
        doAnswer(call -> { remaining.set(call.getArgument(0)); return null; }).when(victim).setNoDamageTicks(anyInt());
        Vector before = victim.getVelocity().clone();
        doAnswer(call -> {
            PlayerVelocityEvent event = call.getArgument(0);
            classic.beginVelocity(event); event.setCancelled(true); classic.velocity(event);
            return null;
        }).when(manager).callEvent(isA(PlayerVelocityEvent.class));
        assertEquals(ClassicCombat.RodResult.REJECTED, classic.rodImpactResult(hook, attacker, victim, 0.4));
        verify(transport).discard(victim, before); verify(transport, never()).send(any(), any());
        verify(victim, never()).playHurtAnimation(anyFloat());
        verify(victim, never()).hideEntity(any(), any());
        verify(hook, never()).setHookedEntity(victim);
        assertEquals(0, remaining.get());
        var sword = new PrePlayerAttackEntityEvent(attacker, victim, true); classic.beforeAttack(sword);
        assertFalse(sword.isCancelled());
    }
    @Test public void regionChangeDuringRodVelocityEventDoesNotReadForeignVictimDuringCleanup() {
        RodVelocity.Transport transport = mock(RodVelocity.Transport.class);
        classic = new ClassicCombat(plugin, () -> settings, store, tracker, RodVelocity.immediate(transport));
        listener = new KnockbackListener(() -> settings, store, tracker, () -> true, classic);
        FishHook hook = rodHook(); rodDamage(hook, event -> {});
        doAnswer(call -> {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(victim)).thenReturn(false);
            clearInvocations(victim);
            return null;
        }).when(manager).callEvent(isA(PlayerVelocityEvent.class));
        assertEquals(ClassicCombat.RodResult.REJECTED, classic.rodImpactResult(hook, attacker, victim, 0.4));
        verifyNoInteractions(victim, transport);
    }
    @Test public void regionChangeDuringRodHurtAnimationDoesNotReadForeignSoundOrWorld() {
        FishHook hook = rodHook(); rodDamage(hook, event -> {});
        doAnswer(call -> {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(victim)).thenReturn(false);
            clearInvocations(victim);
            return null;
        }).when(victim).playHurtAnimation(anyFloat());
        assertEquals(ClassicCombat.RodResult.APPLIED, classic.rodImpactResult(hook, attacker, victim, 0.4));
        verifyNoInteractions(victim);
    }
    @Test public void modeChangeAfterAcceptedVelocityDoesNotPlayOldClassicAnimation() {
        classic = new ClassicCombat(plugin, () -> settings, store, tracker, (player, previous, current) -> {
            settings = settings.withMode(CombatMode.MODERN, ClassicSettings.defaults());
            return true;
        });
        listener = new KnockbackListener(() -> settings, store, tracker, () -> true, classic);
        FishHook hook = rodHook(); rodDamage(hook, event -> {});
        // Delivery succeeded, but its callback changed the mode before the later feedback.
        assertEquals(ClassicCombat.RodResult.APPLIED, classic.rodImpactResult(hook, attacker, victim, 0.4));
        verify(victim, never()).hideEntity(any(), any());
        verify(hook, never()).setHookedEntity(victim);
        verify(victim, never()).playHurtAnimation(anyFloat());
    }
    @Test public void regionChangeAfterAcceptedVelocityDoesNotReadForeignVictimOrAttach() {
        classic = new ClassicCombat(plugin, () -> settings, store, tracker, (player, previous, current) -> {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(victim)).thenReturn(false);
            clearInvocations(victim);
            return true;
        });
        listener = new KnockbackListener(() -> settings, store, tracker, () -> true, classic);
        FishHook hook = rodHook(); rodDamage(hook, event -> {});
        assertEquals(ClassicCombat.RodResult.APPLIED, classic.rodImpactResult(hook, attacker, victim, 0.4));
        verifyNoInteractions(victim);
        verify(hook, never()).setHookedEntity(victim);
    }
    @Test public void lateCancelledRodDamageProducesNoAnimationKnockbackOrImmunity() {
        FishHook hook = rodHook(); rodDamage(hook, event -> event.setCancelled(true));
        classic.rodImpact(hook, attacker, victim, 0.4);
        verify(victim).damage(0, rodSource);
        verify(victim, never()).knockback(anyDouble(), anyDouble(), anyDouble());
        verify(victim, never()).playHurtAnimation(anyFloat()); verify(hook).setHookedEntity(null); verify(hook, never()).remove();
        verify(victim, never()).hideEntity(any(), any());
        verify(hook, never()).setHookedEntity(victim);
        verify(victim, never()).setNoDamageTicks(anyInt());
        assertEquals(0, classic.applied());
        var sword = new PrePlayerAttackEntityEvent(attacker, victim, true); classic.beforeAttack(sword);
        assertFalse(sword.isCancelled());
    }
    @Test public void rejectedNativeDamageWithoutAnEventDoesNotFakeAHit() {
        FishHook hook = rodHook();
        classic.rodImpact(hook, attacker, victim, 0.4); // e.g. PvP disabled or creative immunity.
        verify(victim).damage(0, rodSource);
        verify(victim, never()).knockback(anyDouble(), anyDouble(), anyDouble());
        verify(victim, never()).playHurtAnimation(anyFloat());
        verify(hook).setHookedEntity(null); verify(hook, never()).remove();
    }
    @Test public void rodNeverClearsAnExistingDamageWindowToForceAHit() {
        FishHook hook = rodHook();
        when(victim.getNoDamageTicks()).thenReturn(15); when(victim.getLastDamage()).thenReturn(3d);
        classic.rodImpact(hook, attacker, victim, 0.4);
        verify(victim, never()).damage(anyDouble(), any(DamageSource.class));
        verify(victim, never()).setNoDamageTicks(anyInt()); verify(victim, never()).setLastDamage(anyDouble());
        verify(victim, never()).knockback(anyDouble(), anyDouble(), anyDouble());
    }
    @Test public void modeChangeDuringRodDamageCannotCommitOldClassicEffects() {
        FishHook hook = rodHook(); rodDamage(hook, event -> settings = settings.withMode(CombatMode.MODERN, ClassicSettings.defaults()));
        classic.rodImpact(hook, attacker, victim, 0.4);
        verify(victim, never()).knockback(anyDouble(), anyDouble(), anyDouble());
        verify(victim, never()).playHurtAnimation(anyFloat()); verify(hook).setHookedEntity(null); verify(hook).remove();
    }
    @Test public void nativeKnockbackInsideRodDamageIsSuppressedBeforeOneClassicVector() {
        FishHook hook = rodHook(); rodDamage(hook, event -> {
            var nativeEvent = new EntityKnockbackEvent(victim, EntityKnockbackEvent.Cause.DAMAGE, new Vector(2, 2, 2));
            classic.nativeRodDamageKnockback(nativeEvent); assertTrue(nativeEvent.isCancelled());
        });
        classic.rodImpact(hook, attacker, victim, 0.4);
        assertEquals(1, classic.applied());
        verify(victim, times(1)).knockback(anyDouble(), anyDouble(), anyDouble());
    }
    @Test public void foreignRegionOrExcludedRodTargetIsNeverDamaged() {
        FishHook hook = rodHook();
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(victim)).thenReturn(false);
        clearInvocations(victim);
        classic.rodImpact(hook, attacker, victim, 0.4); verifyNoInteractions(victim);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(victim)).thenReturn(true);
        store.excluded(victim.getUniqueId(), true);
        classic.rodImpact(hook, attacker, victim, 0.4);
        verify(victim, never()).damage(anyDouble(), any(DamageSource.class));
    }
    @Test public void exclusionRemovesClassicAttackSpeedAndImmunityChange() {
        classic.reconcile(victim); store.excluded(victim.getUniqueId(), true); classic.reconcile(victim);
        assertTrue(modifiers.isEmpty()); assertEquals(18, immunity.get());
    }
    @Test public void tickCounterWrapDoesNotBlockForever() {
        assertTrue(ClassicCombat.within(Integer.MAX_VALUE, Integer.MIN_VALUE + 2, 10));
        assertFalse(ClassicCombat.within(Integer.MAX_VALUE, Integer.MIN_VALUE + 9, 10));
        assertFalse(ClassicCombat.within(100, 1, 10));
    }
    @Test public void consecutiveAcceptedHitsReplaceMomentumAndDeduplicateForPlayersAndBots() {
        for (CombatantKind kind : new CombatantKind[]{CombatantKind.PLAYER, CombatantKind.BOT}) {
            when(tracker.kind(victim)).thenReturn(kind);
            for (int index = 0; index < 8; index++) {
                when(victim.getTicksLived()).thenReturn(100 + index * 10 + (kind == CombatantKind.BOT ? 100 : 0));
                Vector before = new Vector(index * 0.2, index % 2 == 0 ? -0.3 : 0.4, -index * 0.1);
                when(victim.getVelocity()).thenReturn(before);
                classic.acceptedDamage(damage());
                var first = knockback(); listener.knockback(first); classic.finishedKnockback(first);
                Vector after = before.clone().add(first.getKnockback());
                assertEquals(0.4, after.getX(), 1e-12);
                assertEquals(0.36075, after.getY(), 1e-12);
                assertEquals(0, after.getZ(), 1e-12);
                when(victim.getVelocity()).thenReturn(after);
                var extra = knockback(); listener.knockback(extra);
                assertTrue(extra.isCancelled());
                var velocity = new PlayerVelocityEvent(victim, new Vector(0.4, 8, 0));
                classic.beginVelocity(velocity); classic.velocity(velocity);
                assertEquals(0.36075, velocity.getVelocity().getY(), 1e-12);
            }
        }
        assertEquals(16, classic.applied());
    }
    @Test public void classicClientSprintProducesConsistentVectorsAfterNativeResetsAndHonorsStop() {
        // Exercise the actual tracker -> accepted damage -> classic vector -> velocity protection path.
        tracker = new CombatTracker(plugin, () -> settings);
        classic = new ClassicCombat(plugin, () -> settings, store, tracker);
        listener = new KnockbackListener(() -> settings, store, tracker, () -> true, classic);
        when(attacker.getType()).thenReturn(EntityType.PLAYER);
        when(victim.getType()).thenReturn(EntityType.PLAYER);
        tracker.toggleSprint(new org.bukkit.event.player.PlayerToggleSprintEvent(attacker, true));
        for (int index = 0; index < 5; index++) {
            int tick = 100 + index * 10;
            when(attacker.getTicksLived()).thenReturn(tick);
            when(victim.getTicksLived()).thenReturn(tick);
            if (index == 4) tracker.toggleSprint(new org.bukkit.event.player.PlayerToggleSprintEvent(attacker, false));
            tracker.preAttack(new PrePlayerAttackEntityEvent(attacker, victim, true));
            when(attacker.isSprinting()).thenReturn(false);
            Vector before = new Vector(index * 0.3, -0.2, 0.5);
            when(victim.getVelocity()).thenReturn(before);
            classic.acceptedDamage(damage());
            var event = knockback(); listener.knockback(event); classic.finishedKnockback(event);
            Vector after = before.clone().add(event.getKnockback());
            assertEquals(index == 4 ? 0.4 : 0.9, after.getX(), 1e-12);
            assertEquals(index == 4 ? 0.36075 : 0.46075, after.getY(), 1e-12);
            assertEquals(0, after.getZ(), 1e-12);
            when(victim.getVelocity()).thenReturn(after);
            var extra = knockback(); listener.knockback(extra); assertTrue(extra.isCancelled());
            var packet = new PlayerVelocityEvent(victim, new Vector(0.4, 8, 0));
            classic.beginVelocity(packet); classic.velocity(packet); assertEquals(after, packet.getVelocity());
        }
        verify(attacker, never()).setSprinting(anyBoolean());
    }
    @Test public void acceptedHitsNeverQueueAnExtraSprintResetEvenWithStopSprintEnabled() {
        for (boolean enabled : new boolean[]{false, true}) {
            settings = settings.withMode(CombatMode.CLASSIC, new ClassicSettings(10, 4, true, true, enabled, true, 2));
            UUID id = attacker.getUniqueId();
            when(tracker.source(eq(attacker), any())).thenReturn(new CombatTracker.Source(id, id, CombatantKind.PLAYER, HitType.MELEE, true, 1));
            classic.acceptedDamage(damage());
        }
        verify(attacker, never()).setSprinting(anyBoolean());
        EntityScheduler scheduler = attacker.getScheduler();
        verify(scheduler, never()).run(any(), any(), any());
    }
    @Test public void recentPlayerJumpIsRecognizedAsJumpResetByClassicCalculation() {
        when(victim.isOnGround()).thenReturn(true);
        classic.reconcile(victim);
        org.bukkit.Input input = mock(org.bukkit.Input.class);
        when(input.isJump()).thenReturn(true);
        classic.input(new org.bukkit.event.player.PlayerInputEvent(victim, input));
        classic.jump(new com.destroystokyo.paper.event.player.PlayerJumpEvent(victim,
                new Location(null, 1, 64, 0), new Location(null, 1, 64.1, 0)));
        when(victim.isOnGround()).thenReturn(false);
        when(victim.getVelocity()).thenReturn(new Vector(0.8, 0.42, -0.2));
        classic.move(new org.bukkit.event.player.PlayerMoveEvent(victim,
                new Location(null, 1, 64, 0), new Location(null, 1, 64.1, 0)));
        when(victim.getTicksLived()).thenReturn(101);
        classic.acceptedDamage(damage());
        HitContext air = new HitContext(victim.getUniqueId(), attacker.getUniqueId(), attacker.getUniqueId(), HitType.MELEE,
                CombatantKind.PLAYER, CombatantKind.PLAYER, "ENTITY_ATTACK", false, false, false, 1, false);
        var calculation = classic.calculate(knockback(), air, KnockbackMathTest.profile(), settings);
        assertNotNull(calculation);
        Vector finalVelocity = new Vector(0.8, 0.42, -0.2).add(calculation.impulse());
        assertEquals(new Vector(0.8, 0.45775, -0.1), finalVelocity);
    }
}

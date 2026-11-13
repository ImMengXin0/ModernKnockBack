package cn.mx.modernkb.profiler;

import cn.mx.modernkb.api.*;
import cn.mx.modernkb.combat.CombatTracker;
import cn.mx.modernkb.config.*;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class KnockbackProfilerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private MockedStatic<Bukkit> bukkit;
    private Plugin plugin;
    private CommandSender sender;
    private Player victim, attacker;
    private CombatTracker tracker;
    private EntityScheduler scheduler;
    private ScheduledExecutorService writer;
    private KnockbackProfiler profiler;
    private Settings settings;
    private UUID worldId;
    private long processed;

    @Before public void setup() throws Exception {
        bukkit = mockStatic(Bukkit.class);
        plugin = mock(Plugin.class); when(plugin.getLogger()).thenReturn(Logger.getLogger("profiler-test"));
        when(plugin.getDataFolder()).thenReturn(temporary.getRoot());
        sender = mock(CommandSender.class); when(sender.getName()).thenReturn("Admin");
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
        bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(global);
        doAnswer(call -> { ((Consumer<?>) call.getArgument(1)).accept(null); return null; })
                .when(global).run(eq(plugin), any());
        victim = mock(Player.class); attacker = mock(Player.class);
        when(victim.getUniqueId()).thenReturn(UUID.randomUUID()); when(victim.getName()).thenReturn("Victim");
        when(attacker.getUniqueId()).thenReturn(UUID.randomUUID()); when(attacker.getName()).thenReturn("Attacker");
        when(victim.getPing()).thenReturn(87); when(attacker.getPing()).thenReturn(40);
        when(victim.getVelocity()).thenReturn(new Vector(.2, .1, 0)); when(victim.getTicksLived()).thenReturn(100);
        when(victim.isOnGround()).thenReturn(true);
        World world = mock(World.class); worldId = UUID.randomUUID(); when(world.getUID()).thenReturn(worldId);
        when(victim.getLocation()).thenReturn(new Location(world, 5, 70, 3));
        scheduler = mock(EntityScheduler.class); when(victim.getScheduler()).thenReturn(scheduler);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(victim)).thenReturn(true);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(attacker)).thenReturn(true);
        tracker = mock(CombatTracker.class); when(tracker.kind(victim)).thenReturn(CombatantKind.PLAYER);
        ModeFiles modes = new ModeFiles(temporary.newFolder("configs").toPath()); modes.initialize();
        settings = modes.load(CombatMode.CLASSIC);
        writer = mock(ScheduledExecutorService.class);
        when(writer.awaitTermination(anyLong(), any())).thenReturn(true);
        profiler = new KnockbackProfiler(plugin, () -> settings,
                new PlayerStore(temporary.getRoot().toPath().resolve("players.yml")), tracker,
                () -> Map.of("processedImpulses", processed), () -> Map.of("server", "Test"), writer, false);
    }
    @After public void cleanup() { profiler.close(); bukkit.close(); }
    private EntityKnockbackEvent event() {
        return new EntityKnockbackEvent(victim, EntityKnockbackEvent.Cause.ENTITY_ATTACK, new Vector(.4, .2, 0));
    }
    private void open() { profiler.command(sender, "open"); }
    private String export() throws Exception {
        profiler.command(sender, "stop");
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(writer).execute(task.capture()); task.getValue().run();
        Path file;
        try (var files = java.nio.file.Files.list(temporary.getRoot().toPath().resolve("profiler"))) {
            file = files.filter(p -> p.toString().endsWith(".html")).findFirst().orElseThrow();
        }
        return java.nio.file.Files.readString(file);
    }

    @Test public void disabledProfilerDoesNotReadEntityOrVectors() {
        EntityKnockbackEvent event = mock(EntityKnockbackEvent.class);
        PlayerVelocityEvent velocity = mock(PlayerVelocityEvent.class);
        profiler.begin(event); profiler.finish(event); profiler.beginVelocity(velocity); profiler.finishVelocity(velocity);
        profiler.context(event, null, null, null, null); profiler.calculation(event, null, false, ""); profiler.applied(event, null);
        verifyNoInteractions(event, velocity, tracker); verify(victim, never()).getVelocity();
    }
    @Test public void wrongRegionDoesNotReadLiveState() throws Exception {
        open(); bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(victim)).thenReturn(false);
        var event = event(); profiler.begin(event); profiler.finish(event);
        verify(victim, never()).getLocation(); verify(victim, never()).getVelocity(); verifyNoInteractions(tracker);
        assertTrue(export().contains("\"observed\":0"));
    }
    @Test public void observesLaterVectorChangesAndCancellationWithoutChangingCombat() throws Exception {
        open(); var event = event(); profiler.begin(event);
        HitContext context = new HitContext(victim.getUniqueId(), attacker.getUniqueId(), attacker.getUniqueId(),
                HitType.MELEE, CombatantKind.PLAYER, CombatantKind.PLAYER, "ENTITY_ATTACK", true, true, false, 1, false, "IRON_SWORD");
        profiler.context(event, context, settings, settings, "default");
        profiler.calculation(event, new Vector(.5, .25, .1), false, "classic-vector");
        profiler.applied(event, new Vector(.55, .3, .1));
        event.setKnockback(new Vector(.6, .35, .1)); event.setCancelled(true); profiler.finish(event);
        assertEquals(new Vector(.6, .35, .1), event.getKnockback()); assertTrue(event.isCancelled());
        verify(victim, never()).setVelocity(any()); verifyNoInteractions(scheduler);
        String html = export();
        assertTrue(html.contains("\"calculated\":[0.5,0.25,0.1]"));
        assertTrue(html.contains("\"applied\":[0.55,0.3,0.1]"));
        assertTrue(html.contains("\"final\":[0.6,0.35,0.1]"));
        assertTrue(html.contains("\"cancelled\":true")); assertTrue(html.contains("\"processed\":true"));
    }
    @Test public void crossRegionAttackerIsNeverRead() throws Exception {
        open(); bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(attacker)).thenReturn(false);
        EntityPushedByEntityAttackEvent event = mock(EntityPushedByEntityAttackEvent.class);
        when(event.getEntity()).thenReturn(victim); when(event.getPushedBy()).thenReturn(attacker);
        when(event.getCause()).thenReturn(EntityKnockbackEvent.Cause.ENTITY_ATTACK); when(event.getKnockback()).thenReturn(new Vector(.4, .2, 0));
        profiler.begin(event); profiler.finish(event);
        verify(attacker, never()).getPing(); verify(attacker, never()).getName(); verify(attacker, never()).getUniqueId();
        assertTrue(export().contains("\"attackerPing\":-1"));
    }
    @Test public void scopedRodCanObserveOwnedShootersPingWithoutNativeSource() throws Exception {
        open(); var event = event(); profiler.begin(event);
        UUID attackerId = attacker.getUniqueId();
        bukkit.when(() -> Bukkit.getPlayer(attackerId)).thenReturn(attacker);
        HitContext context = new HitContext(victim.getUniqueId(), attacker.getUniqueId(), UUID.randomUUID(),
                HitType.FISHING_ROD, CombatantKind.PLAYER, CombatantKind.PLAYER, "UNKNOWN", true, false, false, 1, false, "FISHING_ROD");
        profiler.context(event, context, settings, settings, "fishing_rod"); profiler.finish(event);
        assertTrue(export().contains("\"attackerPing\":40"));
    }
    @Test public void scopedUnownedAttackerKeepsUnknownPingWithoutRead() throws Exception {
        open(); var event = event(); profiler.begin(event);
        UUID attackerId = attacker.getUniqueId(); bukkit.when(() -> Bukkit.getPlayer(attackerId)).thenReturn(attacker);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(attacker)).thenReturn(false);
        clearInvocations(attacker);
        HitContext context = new HitContext(victim.getUniqueId(), attackerId, UUID.randomUUID(),
                HitType.FISHING_ROD, CombatantKind.PLAYER, CombatantKind.PLAYER, "UNKNOWN", true, false, false, 1, false);
        profiler.context(event, context, settings, settings, "fishing_rod"); profiler.finish(event);
        verifyNoInteractions(attacker); assertTrue(export().contains("\"attackerPing\":-1"));
    }
    @Test public void velocityRecordsFinalMonitorValueAndCandidateTiming() throws Exception {
        open(); var event = event(); profiler.begin(event); profiler.finish(event);
        var velocity = new PlayerVelocityEvent(victim, new Vector(.4, .2, 0));
        profiler.beginVelocity(velocity); velocity.setVelocity(new Vector(.7, .25, .1)); profiler.finishVelocity(velocity);
        String html = export();
        assertTrue(html.contains("\"before\":[0.4,0.2,0.0]")); assertTrue(html.contains("\"after\":[0.7,0.25,0.1]"));
        assertTrue(html.contains("\"delayMs\":")); verify(victim, never()).setVelocity(any());
    }
    @Test public void velocityAfterWindowIsNotAttributedToOldHit() throws Exception {
        open(); var event = event(); profiler.begin(event); profiler.finish(event);
        when(victim.getTicksLived()).thenReturn(104);
        var velocity = new PlayerVelocityEvent(victim, new Vector(1, 0, 0)); profiler.beginVelocity(velocity); profiler.finishVelocity(velocity);
        assertTrue(export().contains("\"velocity\":null"));
    }
    @Test public void cancelledSupplementDoesNotStealVelocityOfAcceptedHit() throws Exception {
        open(); var accepted = event(); profiler.begin(accepted); profiler.finish(accepted);
        var repeated = event(); profiler.begin(repeated); repeated.setCancelled(true); profiler.finish(repeated);
        var velocity = new PlayerVelocityEvent(victim, new Vector(.8, .2, 0)); profiler.beginVelocity(velocity); profiler.finishVelocity(velocity);
        String html = export();
        assertTrue(html.contains("\"after\":[0.8,0.2,0.0]"));
        verify(scheduler, times(1)).runDelayed(eq(plugin), any(), any(), eq(3L));
    }
    @Test public void movementOnOwningRegionRecordsDisplacement() throws Exception {
        open(); var event = event(); profiler.begin(event); profiler.finish(event);
        ArgumentCaptor<Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>> callback = ArgumentCaptor.forClass(Consumer.class);
        verify(scheduler).runDelayed(eq(plugin), callback.capture(), any(), eq(3L));
        Location next = victim.getLocation().clone().add(1.2, .5, .2); when(victim.getLocation()).thenReturn(next); when(victim.getTicksLived()).thenReturn(103);
        callback.getValue().accept(null);
        String html = export(); assertTrue(html.contains("\"interrupted\":false")); assertTrue(html.contains("\"ticks\":3"));
        assertTrue(html.contains("\"dy\":0.5"));
    }
    @Test public void newerHitMarksPreviousTrajectoryInterrupted() throws Exception {
        open(); var first = event(); profiler.begin(first); profiler.finish(first);
        var second = event(); profiler.begin(second); profiler.finish(second);
        ArgumentCaptor<Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>> callback = ArgumentCaptor.forClass(Consumer.class);
        verify(scheduler, times(2)).runDelayed(eq(plugin), callback.capture(), any(), eq(3L));
        callback.getAllValues().getFirst().accept(null);
        assertTrue(export().contains("\"interrupted\":true"));
    }
    @Test public void teleportWithinSameWorldInvalidatesTrajectoryAndVelocityAssociation() throws Exception {
        open(); var event = event(); profiler.begin(event); profiler.finish(event);
        var teleport = new PlayerTeleportEvent(victim, victim.getLocation(), victim.getLocation().clone().add(100, 0, 0));
        profiler.teleport(teleport);
        ArgumentCaptor<Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>> callback = ArgumentCaptor.forClass(Consumer.class);
        verify(scheduler).runDelayed(eq(plugin), callback.capture(), any(), eq(3L));
        callback.getValue().accept(null); // The original callback must preserve the explicit reason.
        var velocity = new PlayerVelocityEvent(victim, new Vector(2, 0, 0)); profiler.beginVelocity(velocity); profiler.finishVelocity(velocity);
        String html = export(); assertTrue(html.contains("\"reason\":\"teleport\""));
        assertTrue(html.contains("\"interrupted\":true")); assertTrue(html.contains("\"velocity\":null"));
    }
    @Test public void multipleVelocityEventsRetainCountAndFirstInterval() throws Exception {
        open(); var event = event(); profiler.begin(event); profiler.finish(event);
        for (int i = 0; i < 2; i++) {
            var velocity = new PlayerVelocityEvent(victim, new Vector(.5 + i, .2, 0)); profiler.beginVelocity(velocity); profiler.finishVelocity(velocity);
        }
        String html = export(); assertTrue(html.contains("\"count\":2")); assertTrue(html.contains("\"firstDelayMs\":"));
        assertTrue(html.contains("\"correlation\":\"same-victim-time-window\""));
    }
    @Test public void configChangesKeepBothSnapshotsAndCounterDifferences() throws Exception {
        open(); var first = event(); profiler.begin(first); profiler.finish(first);
        settings = settings.withMode(CombatMode.MODERN, ClassicSettings.defaults()); profiler.configurationChanged(settings);
        var second = event(); profiler.begin(second); profiler.finish(second); processed = 2;
        String html = export(); assertTrue(html.contains("\"mode\":\"classic\"")); assertTrue(html.contains("\"mode\":\"modern\""));
        assertTrue(html.contains("\"processedImpulses\":2"));
    }
    @Test public void reloadBetweenObservationAndCalculationUsesActualCalculationSnapshot() throws Exception {
        open(); var event = event(); profiler.begin(event);
        settings = settings.withMode(CombatMode.MODERN, ClassicSettings.defaults()); profiler.configurationChanged(settings);
        HitContext hit = new HitContext(victim.getUniqueId(), null, null, HitType.MELEE, CombatantKind.UNKNOWN,
                CombatantKind.PLAYER, "ENTITY_ATTACK", true, false, false, 1, false);
        profiler.context(event, hit, settings, settings, "default"); profiler.finish(event);
        String html = export(); assertTrue(html.contains("\"configuration\":2"));
    }
    @Test public void repeatedOpenAndStopCreateOnlyOneReport() throws Exception {
        open(); open(); var event = event(); profiler.begin(event); profiler.finish(event);
        String html = export(); profiler.command(sender, "stop");
        verify(writer, times(1)).execute(any()); assertTrue(html.contains("\"observed\":1"));
    }
    @Test public void failedHtmlDestinationPreservesJsonAndAllowsNewSession() throws Exception {
        open(); var event = event(); profiler.begin(event); profiler.finish(event);
        Path folder = temporary.getRoot().toPath().resolve("profiler"); java.nio.file.Files.createDirectories(folder);
        // A pre-existing report filename must not be overwritten. Resolve the random id from the session.
        var activeField = KnockbackProfiler.class.getDeclaredField("active"); activeField.setAccessible(true);
        Object session = ((java.util.concurrent.atomic.AtomicReference<?>) activeField.get(profiler)).get();
        var recordingField = session.getClass().getDeclaredField("recording"); recordingField.setAccessible(true);
        Object recording = recordingField.get(session);
        var idField = ProfilerRecording.class.getDeclaredField("id"); idField.setAccessible(true);
        String id = (String) idField.get(recording);
        Path collision = folder.resolve("kb-" + id + ".html"); java.nio.file.Files.writeString(collision, "preserve-me");
        profiler.command(sender, "stop");
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class); verify(writer).execute(task.capture()); task.getValue().run();
        assertEquals("preserve-me", java.nio.file.Files.readString(collision));
        assertTrue(java.nio.file.Files.readString(folder.resolve("kb-" + id + ".json")).contains("\"observed\":1"));
        profiler.command(sender, "open"); profiler.command(sender, "status");
        verify(sender, atLeastOnce()).sendMessage(contains("已记录"));
    }
    @Test public void disableExportsActiveSessionIndependentlyOfBukkitAsyncScheduler() throws Exception {
        open(); var event = event(); profiler.begin(event); profiler.finish(event); profiler.close();
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class); verify(writer).execute(task.capture()); task.getValue().run();
        try (var files = java.nio.file.Files.list(temporary.getRoot().toPath().resolve("profiler"))) {
            String html = java.nio.file.Files.readString(files.findFirst().orElseThrow());
            assertTrue(html.contains("\"stopReason\":\"plugin-disable\""));
        }
        verify(writer).shutdown();
    }
}

package cn.mx.modernkb.compat;

import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Uses unflushed writes to distinguish timely transport from the usual tick-end batch. */
public class VelocityPacketFlushTest {
    public record Motion(int id, double x, double y, double z) { public int getId() { return id; } }
    public record Bundle(List<Object> subPackets) {}
    public record Explosion() {}
    private static final Motion SELF = new Motion(42, .5274, .361375, -.3);

    private static final class Fixture implements AutoCloseable {
        final TeamPacketCodec codec = mock(TeamPacketCodec.class);
        final AtomicReference<ClientCollisions.View> view;
        final AtomicInteger flushes = new AtomicInteger();
        final EmbeddedChannel channel = new EmbeddedChannel();
        final ClientCollisions.Overlay overlay;
        Fixture(boolean classic, boolean active) throws Exception {
            var classifier = new VelocityPacketClassifier(Motion.class, Bundle.class, Explosion.class);
            when(codec.requiresVelocityFlush(any(), anyInt())).thenAnswer(call ->
                    classifier.requiresFlush(call.getArgument(0), call.getArgument(1)));
            when(codec.transform(any(), any(), anyBoolean(), anyInt(), anyBoolean()))
                    .thenAnswer(call -> List.of(call.getArgument(0, Object.class)));
            var state = new CollisionTeams<Object>("Alice", "mkb_test", new Object(), value -> value);
            state.enabled(classic); // Consume the initial team update, unrelated to velocity delivery.
            view = new AtomicReference<>(new ClientCollisions.View(classic, true, active));
            overlay = new ClientCollisions.Overlay(channel, state, codec, fail -> { throw new AssertionError(fail); }, 42, view::get);
            channel.pipeline().addLast(new ChannelOutboundHandlerAdapter() {
                @Override public void flush(ChannelHandlerContext context) throws Exception {
                    flushes.incrementAndGet(); super.flush(context);
                }
            });
            channel.pipeline().addLast(overlay);
        }
        @Override public void close() { channel.finishAndReleaseAll(); }
    }

    @Test public void ownVelocityFlushesEarlierPacketsInOrderInBothModes() throws Exception {
        for (boolean classic : new boolean[]{false, true}) try (var f = new Fixture(classic, true)) {
            Object earlier = new Object();
            var waiting = f.channel.write(earlier);
            assertFalse(waiting.isDone());
            assertNull(f.channel.readOutbound());
            var sent = f.channel.write(SELF);
            assertTrue(waiting.isSuccess()); assertTrue(sent.isSuccess());
            assertSame(earlier, f.channel.readOutbound()); assertSame(SELF, f.channel.readOutbound());
            assertNull(f.channel.readOutbound()); assertEquals(1, f.flushes.get());
        }
    }

    @Test public void spectatorsAndUnrelatedPacketsKeepNormalBatching() throws Exception {
        try (var f = new Fixture(false, true)) {
            var other = new Motion(43, 1, 2, 3);
            Object unrelated = new Object();
            f.channel.write(other); f.channel.write(unrelated);
            assertEquals(0, f.flushes.get()); assertNull(f.channel.readOutbound());
            f.channel.flush();
            assertSame(other, f.channel.readOutbound()); assertSame(unrelated, f.channel.readOutbound());
            assertNull(f.channel.readOutbound());
        }
    }

    @Test public void bundledVelocityFlushesWholeBundleOnceWithoutSplitting() throws Exception {
        try (var f = new Fixture(true, true)) {
            Object before = new Object(), after = new Object();
            var bundle = new Bundle(List.of(before, new Bundle(List.of(SELF)), after));
            var sent = f.channel.write(bundle);
            assertTrue(sent.isSuccess()); assertSame(bundle, f.channel.readOutbound());
            assertEquals(List.of(before, new Bundle(List.of(SELF)), after), bundle.subPackets());
            assertNull(f.channel.readOutbound()); assertEquals(1, f.flushes.get());
        }
    }

    @Test public void disabledOrExcludedPlayersResumeCoreBatching() throws Exception {
        try (var f = new Fixture(false, false)) {
            var waiting = f.channel.write(SELF);
            assertFalse(waiting.isDone()); assertNull(f.channel.readOutbound()); assertEquals(0, f.flushes.get());
            f.view.set(new ClientCollisions.View(false, true, true));
            // Changing the setting does not replay or manufacture a velocity.
            assertNull(f.channel.readOutbound());
            f.channel.flush(); assertSame(SELF, f.channel.readOutbound()); assertNull(f.channel.readOutbound());
        }
    }

    @Test public void respawnUsesNewEntityIdAndDoesNotFlushOldEntityMotion() throws Exception {
        try (var f = new Fixture(false, true)) {
            f.overlay.selfEntityId(84);
            f.channel.write(SELF); assertNull(f.channel.readOutbound());
            var current = new Motion(84, .2, .3, .4);
            f.channel.write(current);
            assertSame(SELF, f.channel.readOutbound()); assertSame(current, f.channel.readOutbound());
            assertNull(f.channel.readOutbound()); assertEquals(1, f.flushes.get());
        }
    }

    @Test public void backpressureDoesNotCauseRetriesOrDelayedImpulseReplay() throws Exception {
        try (var f = new Fixture(false, true)) {
            f.channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
            assertFalse(f.channel.isWritable());
            var sent = f.channel.write(SELF);
            assertTrue(sent.isSuccess()); assertSame(SELF, f.channel.readOutbound());
            f.channel.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
            f.channel.runPendingTasks(); f.channel.runScheduledPendingTasks(); f.channel.flush();
            assertNull(f.channel.readOutbound());
        }
    }

    @Test public void filteredPacketCompletesPromiseWithoutFlushingOrReplaying() throws Exception {
        try (var f = new Fixture(true, true)) {
            doReturn(List.of()).when(f.codec).transform(eq(SELF), any(), anyBoolean(), anyInt(), anyBoolean());
            var sent = f.channel.write(SELF);
            assertTrue(sent.isSuccess()); assertNull(f.channel.readOutbound()); assertEquals(0, f.flushes.get());
            f.channel.runPendingTasks(); assertNull(f.channel.readOutbound());
        }
    }

    @Test public void explosionsAreFlushedAsSingleUnmodifiedMessages() throws Exception {
        try (var f = new Fixture(false, true)) {
            var explosion = new Explosion();
            f.channel.write(explosion);
            assertSame(explosion, f.channel.readOutbound()); assertNull(f.channel.readOutbound()); assertEquals(1, f.flushes.get());
        }
    }

    @Test public void failedWriteIsNeverRetried() throws Exception {
        try (var f = new Fixture(false, true)) {
            AtomicInteger writes = new AtomicInteger();
            f.channel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
                @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                    writes.incrementAndGet(); promise.setFailure(new IllegalStateException("disconnected"));
                }
            });
            var sent = f.channel.write(SELF);
            assertTrue(sent.isDone()); assertFalse(sent.isSuccess());
            f.channel.runPendingTasks(); f.channel.runScheduledPendingTasks();
            assertEquals(1, writes.get()); assertNull(f.channel.readOutbound());
        }
    }
}

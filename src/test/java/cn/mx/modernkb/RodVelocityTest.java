package cn.mx.modernkb;

import cn.mx.modernkb.compat.RodVelocity;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.Vector;
import org.junit.*;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class RodVelocityTest {
    private MockedStatic<Bukkit> bukkit;
    private Player player;
    private PluginManager events;
    private RodVelocity.Transport transport;
    private RodVelocity velocity;
    private final Vector before = new Vector(0.1, -0.1, 0);
    @Before public void setup() {
        bukkit = mockStatic(Bukkit.class); player = mock(Player.class); events = mock(PluginManager.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(events);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(true);
        when(player.getVelocity()).thenReturn(new Vector(0.5, 0.34, 0));
        transport = mock(RodVelocity.Transport.class); velocity = RodVelocity.immediate(transport);
    }
    @After public void close() { bukkit.close(); }
    @Test public void eventRunsOnceThenFinalVelocityIsSentWithoutScheduling() {
        doAnswer(call -> { ((PlayerVelocityEvent)call.getArgument(0)).setVelocity(new Vector(0.4, 0.3, 0)); return null; })
                .when(events).callEvent(any(PlayerVelocityEvent.class));
        assertTrue(velocity.deliver(player, before, () -> true));
        var order = inOrder(events, transport);
        order.verify(events).callEvent(any(PlayerVelocityEvent.class));
        order.verify(transport).send(player, new Vector(0.4, 0.3, 0));
        verify(player, never()).getScheduler(); verify(transport, never()).discard(any(), any());
    }
    @Test public void cancellationDiscardsPendingVelocityAndDoesNotSend() {
        Runnable feedback = mock(Runnable.class);
        doAnswer(call -> { ((PlayerVelocityEvent)call.getArgument(0)).setCancelled(true); return null; })
                .when(events).callEvent(any(PlayerVelocityEvent.class));
        assertFalse(velocity.deliver(player, before, () -> true, feedback));
        verify(transport).discard(player, before); verify(transport, never()).send(any(), any());
        verifyNoInteractions(feedback);
    }
    @Test public void nestedHitCannotBeOverwrittenOrClearedByOldRod() {
        Runnable feedback = mock(Runnable.class);
        AtomicBoolean current = new AtomicBoolean(true);
        doAnswer(call -> { current.set(false); return null; }).when(events).callEvent(any());
        assertFalse(velocity.deliver(player, before, current::get, feedback)); verifyNoInteractions(transport, feedback);
    }
    @Test public void foreignRegionNeverReadsVelocityOrFiresAnEvent() {
        Runnable feedback = mock(Runnable.class);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(false);
        assertFalse(velocity.deliver(player, before, () -> true, feedback));
        verify(player, never()).getVelocity(); verifyNoInteractions(transport, events, feedback);
    }
    @Test public void acceptedFeedbackRunsAfterVelocityListenersAndBeforeMotion() {
        Runnable feedback = mock(Runnable.class);
        doAnswer(call -> { ((PlayerVelocityEvent)call.getArgument(0)).setVelocity(new Vector(0.4, 0.3, 0)); return null; })
                .when(events).callEvent(any(PlayerVelocityEvent.class));
        assertTrue(velocity.deliver(player, before, () -> true, feedback));
        var order = inOrder(events, feedback, transport);
        order.verify(events).callEvent(any(PlayerVelocityEvent.class));
        order.verify(feedback).run();
        order.verify(transport).send(player, new Vector(0.4, 0.3, 0));
        verify(feedback, times(1)).run();
        verify(player, never()).getScheduler();
    }
    @Test public void newerHitDuringFeedbackKeepsItsVelocity() {
        AtomicBoolean current = new AtomicBoolean(true);
        assertFalse(velocity.deliver(player, before, current::get, () -> current.set(false)));
        verifyNoInteractions(transport);
    }
    @Test public void regionChangeDuringFeedbackDoesNotSendOrDiscard() {
        assertFalse(velocity.deliver(player, before, () -> true,
                () -> bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(false)));
        verifyNoInteractions(transport);
    }
    @Test public void failedFeedbackDoesNotSendMotion() {
        RuntimeException failure = new IllegalStateException("feedback failed");
        assertSame(failure, assertThrows(RuntimeException.class,
                () -> velocity.deliver(player, before, () -> true, () -> { throw failure; })));
        verifyNoInteractions(transport);
    }
    @Test public void invalidVelocityDoesNotProduceFeedback() {
        Runnable feedback = mock(Runnable.class);
        when(player.getVelocity()).thenReturn(new Vector(Double.NaN, 0.3, 0));
        assertThrows(IllegalArgumentException.class, () -> velocity.deliver(player, before, () -> true, feedback));
        verifyNoInteractions(feedback, transport);
    }
    @Test public void deferredFeedbackPrecedesVelocityAndRequiresCurrentHit() {
        Runnable feedback = mock(Runnable.class);
        assertTrue(RodVelocity.deferred().deliver(player, before, () -> true, feedback));
        var order = inOrder(feedback, player);
        order.verify(feedback).run();
        order.verify(player).setVelocity(new Vector(0.5, 0.34, 0));
        verifyNoInteractions(events, transport);
        clearInvocations(player);
        AtomicBoolean current = new AtomicBoolean(true);
        assertFalse(RodVelocity.deferred().deliver(player, before, current::get, () -> current.set(false)));
        verify(player, never()).setVelocity(any());
    }
    @Test public void threeArgumentCustomImplementationsKeepTheirFunctionalInterface() {
        Runnable delivered = mock(Runnable.class), feedback = mock(Runnable.class);
        RodVelocity custom = (target, previous, current) -> { delivered.run(); return true; };
        assertTrue(custom.deliver(player, before, () -> true, feedback));
        var order = inOrder(delivered, feedback);
        order.verify(delivered).run();
        order.verify(feedback).run();
        verifyNoInteractions(events, transport);
    }
    @Test public void rejectedOrSupersededCustomDeliveryDoesNotProduceFeedback() {
        Runnable feedback = mock(Runnable.class);
        RodVelocity rejected = (target, previous, current) -> false;
        assertFalse(rejected.deliver(player, before, () -> true, feedback));
        RodVelocity superseded = (target, previous, current) -> true;
        assertTrue(superseded.deliver(player, before, () -> false, feedback));
        verifyNoInteractions(feedback);
    }
}

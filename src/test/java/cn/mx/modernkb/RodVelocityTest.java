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
        doAnswer(call -> { ((PlayerVelocityEvent)call.getArgument(0)).setCancelled(true); return null; })
                .when(events).callEvent(any(PlayerVelocityEvent.class));
        assertFalse(velocity.deliver(player, before, () -> true));
        verify(transport).discard(player, before); verify(transport, never()).send(any(), any());
    }
    @Test public void nestedHitCannotBeOverwrittenOrClearedByOldRod() {
        AtomicBoolean current = new AtomicBoolean(true);
        doAnswer(call -> { current.set(false); return null; }).when(events).callEvent(any());
        assertFalse(velocity.deliver(player, before, current::get)); verifyNoInteractions(transport);
    }
    @Test public void foreignRegionNeverReadsVelocityOrFiresAnEvent() {
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(false);
        assertFalse(velocity.deliver(player, before, () -> true));
        verify(player, never()).getVelocity(); verifyNoInteractions(transport, events);
    }
}

package cn.mx.modernkb.combat;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import java.util.UUID;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ClassicJumpTrackerTest {
    private ClassicJumpTracker tracker;
    private Player player;
    private World world;
    private Object configuration;

    @Before public void setup() {
        tracker = new ClassicJumpTracker();
        configuration = new Object();
        player = mock(Player.class);
        world = mock(World.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getTicksLived()).thenReturn(100);
    }
    private Location from() { return new Location(world, 1, 64, 2); }
    private Location to() { return new Location(world, 1.2, 64.42, 2.1); }
    private Input input(boolean jump) {
        Input input = mock(Input.class);
        when(input.isJump()).thenReturn(jump);
        return input;
    }
    private void press(boolean jump) { tracker.input(new PlayerInputEvent(player, input(jump))); }
    private PlayerJumpEvent jump() {
        PlayerJumpEvent jump = new PlayerJumpEvent(player, from(), to());
        tracker.jump(jump, configuration);
        return jump;
    }
    private PlayerMoveEvent move() {
        PlayerMoveEvent move = new PlayerMoveEvent(player, from(), to());
        tracker.move(move);
        return move;
    }
    private void accepted() { press(true); jump(); move(); }
    private boolean recent() { return tracker.recent(player, 2, configuration); }

    @Test public void genuineJumpRequiresInputJumpEventAndAcceptedMovement() {
        press(true);
        assertFalse(recent());
        jump();
        assertFalse(recent());
        move();
        assertTrue(recent());
    }
    @Test public void movementAndJumpEventsWithoutJumpInputDoNotGrantReset() {
        jump(); move();
        assertFalse(recent());
    }
    @Test public void movingUpWithJumpHeldDoesNotInventAMissingNativeJump() {
        press(true); move();
        assertFalse(recent());
    }
    @Test public void sameTickInputMayArriveAfterJumpAndMovement() {
        jump(); move();
        assertFalse(recent());
        press(true);
        assertTrue(recent());
    }
    @Test public void nextTickJumpInputCannotReclassifyEarlierKnockbackLift() {
        jump(); move();
        when(player.getTicksLived()).thenReturn(101);
        press(true);
        assertFalse(recent());
    }
    @Test public void releasingJumpDoesNotRemoveAcceptedJumpEvidence() {
        accepted();
        press(false);
        assertTrue(recent());
        when(player.getTicksLived()).thenReturn(101);
        assertTrue(recent());
    }
    @Test public void heldInputAtLoadCanConfirmFirstJumpWithoutANewInputEvent() {
        Input current = input(true);
        when(player.getCurrentInput()).thenReturn(current);
        jump(); move();
        assertTrue(recent());
    }
    @Test public void inputEventOverridesTheServerInputUntilItsNativeStateIsUpdated() {
        Input current = input(true);
        when(player.getCurrentInput()).thenReturn(current);
        press(false);
        jump(); move();
        assertFalse(recent());
    }
    @Test public void cancelledNativeJumpCannotBeConfirmed() {
        press(true);
        PlayerJumpEvent jump = new PlayerJumpEvent(player, from(), to());
        jump.setCancelled(true);
        tracker.jump(jump, configuration); move();
        assertFalse(recent());
    }
    @Test public void laterJumpCancellationInvalidatesMovementAlreadyObserved() {
        press(true);
        PlayerJumpEvent jump = jump(); move();
        jump.setCancelled(true);
        assertFalse(recent());
    }
    @Test public void cancelledMovementCannotConfirmJump() {
        press(true); jump();
        PlayerMoveEvent move = new PlayerMoveEvent(player, from(), to());
        move.setCancelled(true); tracker.move(move);
        assertFalse(recent());
    }
    @Test public void laterMovementCancellationInvalidatesAcceptedCandidate() {
        press(true); jump();
        PlayerMoveEvent move = move();
        move.setCancelled(true);
        assertFalse(recent());
    }
    @Test public void redirectedMovementCannotConfirmOriginalJump() {
        press(true); jump();
        tracker.move(new PlayerMoveEvent(player, from(), new Location(world, 1.2, 63, 2.1)));
        assertFalse(recent());
    }
    @Test public void laterMovementTargetChangeInvalidatesAcceptedCandidate() {
        press(true); jump();
        PlayerMoveEvent move = move();
        move.setTo(new Location(world, 1.2, 70, 2.1));
        assertFalse(recent());
    }
    @Test public void laterNativeJumpOriginChangeInvalidatesCandidate() {
        press(true);
        PlayerJumpEvent jump = jump(); move();
        jump.getFrom().setY(70);
        assertFalse(recent());
    }
    @Test public void unrelatedLaterMovementCannotConfirmUnfinishedJump() {
        press(true); jump();
        when(player.getTicksLived()).thenReturn(101);
        move();
        assertFalse(recent());
    }
    @Test public void acceptedStrafeAndLookChangesDoNotEraseARecentJump() {
        accepted();
        when(player.getTicksLived()).thenReturn(101);
        tracker.move(new PlayerMoveEvent(player, to(), new Location(world, 1.3, 64.7, 1.7, 120, 20)));
        assertTrue(recent());
    }
    @Test public void cancelledUnrelatedMovementDoesNotEraseAcceptedJump() {
        accepted();
        PlayerMoveEvent movement = new PlayerMoveEvent(player, to(), new Location(world, 1.3, 64.7, 1.7));
        movement.setCancelled(true); tracker.move(movement);
        assertTrue(recent());
    }
    @Test public void landingClearsJumpButHeldInputCanStartANewJump() {
        accepted();
        when(player.isOnGround()).thenReturn(true);
        tracker.move(new PlayerMoveEvent(player, to(), from()));
        assertFalse(recent());
        when(player.isOnGround()).thenReturn(false);
        when(player.getTicksLived()).thenReturn(110);
        jump(); move();
        assertTrue(recent());
    }
    @Test public void windowUsesVictimTicksIncludingBoundaryAndWrap() {
        accepted();
        when(player.getTicksLived()).thenReturn(102);
        assertTrue(recent());
        when(player.getTicksLived()).thenReturn(103);
        assertFalse(recent());
        when(player.getTicksLived()).thenReturn(Integer.MAX_VALUE);
        accepted();
        when(player.getTicksLived()).thenReturn(Integer.MIN_VALUE);
        assertTrue(recent());
        when(player.getTicksLived()).thenReturn(Integer.MIN_VALUE + 2);
        assertFalse(recent());
    }
    @Test public void resetClearsBothInputAndCandidate() {
        accepted();
        tracker.clear(player.getUniqueId());
        jump(); move();
        assertFalse(recent());
        accepted();
        tracker.clear();
        jump(); move();
        assertFalse(recent());
    }
    @Test public void staleEventCannotReintroduceJumpEvidenceAfterAConfigurationSwap() {
        accepted();
        configuration = new Object();
        assertFalse(recent());
        jump(); move();
        assertTrue(recent());
    }
}

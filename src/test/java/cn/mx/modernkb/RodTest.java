package cn.mx.modernkb;

import cn.mx.modernkb.rod.RodListener;
import org.bukkit.event.player.PlayerFishEvent;
import org.junit.Test;
import static org.junit.Assert.*;

public class RodTest {
    @Test public void firstCastAndZeroIntervalAreNotBlocked() {
        assertFalse(RodListener.blocked(null, 1, 5)); assertFalse(RodListener.blocked(10, 10, 0));
    }
    @Test public void castBoundaryAndTickWrapAreCorrect() {
        assertTrue(RodListener.blocked(10, 13, 4)); assertFalse(RodListener.blocked(10, 14, 4));
        assertTrue(RodListener.blocked(Integer.MAX_VALUE, Integer.MIN_VALUE, 4));
        assertFalse(RodListener.blocked(100, 0, 4));
    }
    @Test public void failedBiteIsNotReelOrCooldownReset() {
        assertFalse(RodListener.isReel(PlayerFishEvent.State.FAILED_ATTEMPT));
        assertFalse(RodListener.isReel(PlayerFishEvent.State.BITE));
        assertFalse(RodListener.isReel(PlayerFishEvent.State.FISHING));
        assertTrue(RodListener.isReel(PlayerFishEvent.State.REEL_IN));
        assertTrue(RodListener.isReel(PlayerFishEvent.State.CAUGHT_ENTITY));
    }
}

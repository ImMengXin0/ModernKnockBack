package cn.mx.modernkb.compat;

import org.junit.Test;
import static org.junit.Assert.*;

public class HurtAnimationFilterTest {
    public record Packet(int id, float yaw) {}

    @Test public void ownDirectionalHurtAnimationIsCopiedToFixedTilt() throws Exception {
        HurtAnimationFilter filter = new HurtAnimationFilter(Packet.class);
        for (float yaw : new float[]{-180f, -90f, 35f, 90f, 180f, 720f}) {
            Packet original = new Packet(42, yaw);
            Packet changed = (Packet) filter.transform(original, 42);
            assertNotSame(original, changed);
            assertEquals(42, changed.id());
            assertEquals(0f, changed.yaw(), 0f);
            assertEquals(yaw, original.yaw(), 0f);
        }
    }
    @Test public void otherEntitiesAndUnrelatedPacketsRemainUnchanged() throws Exception {
        HurtAnimationFilter filter = new HurtAnimationFilter(Packet.class);
        Packet other = new Packet(43, 75f);
        Object unrelated = new Object();
        assertSame(other, filter.transform(other, 42));
        assertSame(unrelated, filter.transform(unrelated, 42));
    }
    @Test public void absentOrZeroTiltNeedsNoReplacement() throws Exception {
        HurtAnimationFilter filter = new HurtAnimationFilter(Packet.class);
        Packet zero = new Packet(42, 0f);
        Packet directional = new Packet(42, 90f);
        assertSame(zero, filter.transform(zero, 42));
        assertSame(directional, filter.transform(directional, -1));
    }
    @Test public void aNewLocalEntityIdStopsRewritingTheOldOne() throws Exception {
        HurtAnimationFilter filter = new HurtAnimationFilter(Packet.class);
        Packet previous = new Packet(42, 35f);
        Packet respawned = new Packet(84, 80f);
        assertEquals(new Packet(42, 0f), filter.transform(previous, 42));
        assertSame(previous, filter.transform(previous, 84));
        assertEquals(new Packet(84, 0f), filter.transform(respawned, 84));
    }
}

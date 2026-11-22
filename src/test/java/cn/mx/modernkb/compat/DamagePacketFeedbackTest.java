package cn.mx.modernkb.compat;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DamagePacketFeedbackTest {
    public record Damage(int entityId, int sourceCauseId, int sourceDirectId) {}
    public record Bundle(List<?> subPackets) {}
    @Test public void nestedBundlesReportOnlyDirectDamageForThisConnectionInOrder() throws Exception {
        var reader = DamagePacketFeedback.create(Damage.class, Bundle.class, Bundle.class.getMethod("subPackets"));
        var ids = new ArrayList<Integer>();
        reader.visit(new Bundle(List.of(new Damage(41,3,3), new Damage(42,3,77), new Damage(42,-1,-1),
                new Bundle(List.of(new Damage(42,99,99))), new Damage(42,3,3))),42,(id,nanos) -> ids.add(id));
        assertEquals(List.of(99,3), ids);
    }
}

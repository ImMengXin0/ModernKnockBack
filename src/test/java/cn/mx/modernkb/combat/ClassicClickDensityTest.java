package cn.mx.modernkb.combat;

import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClassicClickDensityTest {
    private final ClassicClickDensity density = new ClassicClickDensity();
    private final UUID player = UUID.randomUUID();
    private final Object configuration = new Object();
    private void clicks(int count, int tick) {
        for (int i = 0; i < count; i++) density.record(player, tick, configuration);
    }
    @Test public void sameTickBurstHasBoundedStepsAndDoesNotAccumulatePastTheCap() {
        assertEquals(0, density.reduction(player, 100, configuration), 0);
        for (int count = 1; count <= 5; count++) {
            clicks(1, 100);
            assertEquals((count - 1) * 0.05, density.reduction(player, 100, configuration), 1e-12);
        }
        clicks(10000, 100);
        assertEquals(0.2, density.reduction(player, 100, configuration), 1e-12);
        // Querying another hit never multiplies or consumes a persistent KB value.
        assertEquals(0.2, density.reduction(player, 100, configuration), 1e-12);
    }
    @Test public void sustainedOneClickPerTickNeverBecomesABurst() {
        for (int tick = 100; tick < 160; tick++) {
            clicks(1, tick);
            assertEquals(0, density.reduction(player, tick, configuration), 0);
        }
    }
    @Test public void burstSurvivesOneBoundaryAndExpiresEvenWhenOrdinaryClicksContinue() {
        clicks(5, 100);
        assertEquals(0.2, density.reduction(player, 101, configuration), 1e-12);
        clicks(1, 101);
        assertEquals(0.2, density.reduction(player, 101, configuration), 1e-12);
        clicks(1, 102);
        assertEquals(0, density.reduction(player, 102, configuration), 0);
    }
    @Test public void idleGapsDoNotCarryOldBurstsIntoTheNextFight() {
        clicks(5, 100);
        assertEquals(0, density.reduction(player, 102, configuration), 0);
        clicks(1, 200);
        assertEquals(0, density.reduction(player, 200, configuration), 0);
        clicks(4, 200);
        assertEquals(0.2, density.reduction(player, 200, configuration), 1e-12);
    }
    @Test public void reloadAndPlayerTickResetInvalidatePreviousEvidence() {
        clicks(5, 100);
        assertEquals(0, density.reduction(player, 100, new Object()), 0);
        clicks(5, 100);
        assertEquals(0, density.reduction(player, 0, configuration), 0);
        clicks(1, 0);
        assertEquals(0, density.reduction(player, 0, configuration), 0);
    }
    @Test public void consecutiveTicksAcrossIntegerOverflowStillExpireNormally() {
        clicks(5, Integer.MAX_VALUE);
        assertEquals(0.2, density.reduction(player, Integer.MIN_VALUE, configuration), 1e-12);
        assertEquals(0, density.reduction(player, Integer.MIN_VALUE + 1, configuration), 0);
    }
    @Test public void samplesArePerPlayerAndLifecycleClearingIsImmediate() {
        UUID other = UUID.randomUUID();
        clicks(5, 100);
        density.record(other, 100, configuration);
        assertEquals(0, density.reduction(other, 100, configuration), 0);
        density.clear(player);
        assertEquals(0, density.reduction(player, 100, configuration), 0);
        clicks(5, 100);
        density.clear();
        assertEquals(0, density.reduction(player, 100, configuration), 0);
    }
}

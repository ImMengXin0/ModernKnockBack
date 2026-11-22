package cn.mx.modernkb.compat;

import java.util.UUID;
import java.util.function.Consumer;

/** Read-only connection observations; implementations must never access entities on the event loop. */
@FunctionalInterface
public interface MotionWriteProbe {
    Capture motionTarget(UUID recipient);

    /** Pending direct PvP damage, if recording; the packet may reach Netty before the KB event. */
    default Feedback feedbackTarget(UUID recipient) { return null; }
    @FunctionalInterface interface Feedback { void submitted(int sourceEntityId, long nanos); }

    @FunctionalInterface
    interface Capture {
        /** Timestamp of submission to the outbound pipeline. Completion is local, never a client ACK. */
        Consumer<Boolean> submitted(long nanos, double x, double y, double z, boolean bundled);
    }
}

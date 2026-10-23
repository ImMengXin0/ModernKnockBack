package cn.mx.modernkb.compat;

import java.lang.reflect.Field;

/** Recognizes Leaf's two synchronous velocity events for one native melee delivery. */
public final class LeafVelocityDispatch {
    public enum Stage { NONE, BEFORE_FLUSH, IN_FLUSH }
    private static final String PLAYER = "net.minecraft.world.entity.player.Player";
    private static final String TRACKER = "net.minecraft.server.level.ServerEntity";
    private static final Field FLUSH = flushSetting();
    private static final StackWalker STACK = StackWalker.getInstance();

    private LeafVelocityDispatch() {}

    public static Stage stage() {
        if (FLUSH == null) return Stage.NONE;
        try {
            if (!FLUSH.getBoolean(null)) return Stage.NONE;
        } catch (IllegalAccessException | RuntimeException ignored) {
            return Stage.NONE;
        }
        return STACK.walk(frames -> {
            var calls = frames.iterator();
            while (calls.hasNext()) {
                var frame = calls.next();
                if (frame.getClassName().equals(TRACKER) && frame.getMethodName().equals("sendChanges")) {
                    // A plugin calling the tracker, or a later tracking tick, is not this flush.
                    return calls.hasNext() && meleeDelivery(calls.next()) ? Stage.IN_FLUSH : Stage.NONE;
                }
                if (meleeDelivery(frame)) return Stage.BEFORE_FLUSH;
            }
            return Stage.NONE;
        });
    }

    private static boolean meleeDelivery(StackWalker.StackFrame frame) {
        return frame.getClassName().equals(PLAYER) && frame.getMethodName().equals("causeExtraKnockback");
    }

    private static Field flushSetting() {
        try {
            return Class.forName("org.dreeam.leaf.config.modules.gameplay.Knockback", false,
                    LeafVelocityDispatch.class.getClassLoader()).getField("flushKnockback");
        } catch (ReflectiveOperationException | LinkageError | SecurityException ignored) {
            return null;
        }
    }
}

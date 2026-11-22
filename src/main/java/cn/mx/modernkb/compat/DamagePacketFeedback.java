package cn.mx.modernkb.compat;

import java.lang.reflect.Method;

/** Only direct, attributed damage feedback addressed to the victim's own connection. */
record DamagePacketFeedback(Class<?> packet, Method target, Method cause, Method direct, Class<?> bundle, Method children) {
    static DamagePacketFeedback forServer(Class<?> bundle, Method children) {
        try { return create(Class.forName("net.minecraft.network.protocol.game.ClientboundDamageEventPacket"), bundle, children); }
        catch (ReflectiveOperationException | LinkageError unavailable) { return null; }
    }
    static DamagePacketFeedback create(Class<?> type, Class<?> bundle, Method children) throws ReflectiveOperationException {
        return new DamagePacketFeedback(type, type.getMethod("entityId"), type.getMethod("sourceCauseId"), type.getMethod("sourceDirectId"), bundle, children);
    }
    void visit(Object value, int self, MotionWriteProbe.Feedback capture) throws ReflectiveOperationException {
        if (self < 0) return;
        if (packet.isInstance(value) && (int) target.invoke(value) == self) {
            int attacker = (int) cause.invoke(value);
            if (attacker >= 0 && (int) direct.invoke(value) == attacker) capture.submitted(attacker, System.nanoTime());
        } else if (bundle.isInstance(value)) {
            for (Object child : (Iterable<?>) children.invoke(value)) visit(child, self, capture);
        }
    }
}

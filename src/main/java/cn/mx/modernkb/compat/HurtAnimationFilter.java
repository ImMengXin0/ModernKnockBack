package cn.mx.modernkb.compat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Keeps the local player's old fixed hurt tilt without removing the hurt animation. */
final class HurtAnimationFilter {
    private final Class<?> packet;
    private final Constructor<?> constructor;
    private final Method id, yaw;

    HurtAnimationFilter() throws ReflectiveOperationException {
        this(Class.forName("net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket"));
    }

    HurtAnimationFilter(Class<?> packet) throws ReflectiveOperationException {
        this.packet = packet;
        constructor = packet.getConstructor(int.class, float.class);
        id = packet.getMethod("id");
        yaw = packet.getMethod("yaw");
        // Resolving Method objects alone leaves their first invocation on the Netty thread.
        // Exercise the real copy path at enable with an in-memory packet; nothing is sent.
        transform(constructor.newInstance(0, 90f), 0);
    }

    Object transform(Object value, int selfEntityId) throws ReflectiveOperationException {
        if (selfEntityId < 0 || !packet.isInstance(value) || (int) id.invoke(value) != selfEntityId
                || (float) yaw.invoke(value) == 0f) return value;
        // The packet may also be sent to other viewers. Copy it instead of changing its fields.
        return constructor.newInstance(selfEntityId, 0f);
    }
}

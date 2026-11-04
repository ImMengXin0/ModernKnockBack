package cn.mx.modernkb.compat;

import java.lang.reflect.Method;
import java.util.List;

/** Reads immutable outgoing packets on the connection event loop; never edits or resends them. */
final class VelocityPacketClassifier {
    private final Class<?> motion, bundle, explosion;
    private final Method entityId, children;

    VelocityPacketClassifier() throws ReflectiveOperationException {
        this(Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket"),
                Class.forName("net.minecraft.network.protocol.game.ClientboundBundlePacket"),
                Class.forName("net.minecraft.network.protocol.game.ClientboundExplodePacket"));
        // Link reflection before the first real hit, matching the other startup warmups.
        Class<?> vector = Class.forName("net.minecraft.world.phys.Vec3");
        Object sample = motion.getConstructor(int.class, vector).newInstance(0, vector.getField("ZERO").get(null));
        requiresFlush(bundle.getConstructor(Iterable.class).newInstance(List.of(sample)), 0);
    }

    VelocityPacketClassifier(Class<?> motion, Class<?> bundle, Class<?> explosion) throws ReflectiveOperationException {
        this.motion = motion; this.bundle = bundle; this.explosion = explosion;
        entityId = motion.getMethod("getId");
        children = bundle.getMethod("subPackets");
    }

    boolean requiresFlush(Object value, int selfEntityId) throws ReflectiveOperationException {
        if (selfEntityId < 0) return false;
        if (motion.isInstance(value)) return (int) entityId.invoke(value) == selfEntityId;
        // Explosion packets carry optional recipient-specific knockback rather than an entity ID.
        if (explosion.isInstance(value)) return true;
        if (bundle.isInstance(value)) {
            for (Object child : (Iterable<?>) children.invoke(value))
                if (requiresFlush(child, selfEntityId)) return true;
        }
        return false;
    }
}

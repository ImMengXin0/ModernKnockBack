package cn.mx.modernkb.compat;

import java.lang.reflect.Method;

/** Reads immutable packet data only; safe on a connection's event loop. */
final class SweepEffectFilter {
    private static final String SWEEP = "minecraft:entity.player.attack.sweep";
    private final Class<?> positional = Class.forName("net.minecraft.network.protocol.game.ClientboundSoundPacket");
    private final Class<?> attached = Class.forName("net.minecraft.network.protocol.game.ClientboundSoundEntityPacket");
    private final Method positionalSound = positional.getMethod("getSound");
    private final Method attachedSound = attached.getMethod("getSound");
    private final Method value = Class.forName("net.minecraft.core.Holder").getMethod("value");
    private final Method location = Class.forName("net.minecraft.sounds.SoundEvent").getMethod("location");
    private final Class<?> particles = Class.forName("net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket");
    private final Method particle = particles.getMethod("getParticle");
    private final Method particleType = Class.forName("net.minecraft.core.particles.ParticleOptions").getMethod("getType");
    private final Object sweepParticle = Class.forName("net.minecraft.core.particles.ParticleTypes").getField("SWEEP_ATTACK").get(null);

    SweepEffectFilter() throws ReflectiveOperationException {}

    boolean matches(Object packet) throws ReflectiveOperationException {
        if (particles.isInstance(packet)) return particleType.invoke(particle.invoke(packet)) == sweepParticle;
        Method sound = positional.isInstance(packet) ? positionalSound : attached.isInstance(packet) ? attachedSound : null;
        if (sound == null) return false;
        // Resolve the identifier, not a numeric registry ID or Holder identity. Direct holders
        // (e.g. plugin sounds) and registered holders must behave identically.
        return SWEEP.equals(location.invoke(value.invoke(sound.invoke(packet))).toString());
    }
}

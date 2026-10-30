package cn.mx.modernkb.compat;

import java.lang.reflect.Method;
import java.util.Set;

/** Reads immutable packet data only; safe on a connection's event loop. */
final class ClassicEffectFilter {
    private static final String SWEEP = "minecraft:entity.player.attack.sweep";
    // These attack accents were added with modern combat. Keep the victim's actual hurt
    // sound, fishing sounds, critical/enchantment particles and arm animations untouched.
    private static final Set<String> MODERN_ATTACK_SOUNDS = Set.of(
            "minecraft:entity.player.attack.strong", "minecraft:entity.player.attack.weak",
            "minecraft:entity.player.attack.knockback", "minecraft:entity.player.attack.crit",
            "minecraft:entity.player.attack.nodamage");
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
    private final Object damageParticle = Class.forName("net.minecraft.core.particles.ParticleTypes").getField("DAMAGE_INDICATOR").get(null);

    ClassicEffectFilter() throws ReflectiveOperationException {}

    boolean matches(Object packet, boolean legacyHitEffects) throws ReflectiveOperationException {
        if (particles.isInstance(packet)) {
            Object type = particleType.invoke(particle.invoke(packet));
            return type == sweepParticle || legacyHitEffects && type == damageParticle;
        }
        Method sound = positional.isInstance(packet) ? positionalSound : attached.isInstance(packet) ? attachedSound : null;
        if (sound == null) return false;
        // Resolve the identifier, not a numeric registry ID or Holder identity. Direct holders
        // (e.g. plugin sounds) and registered holders must behave identically.
        String key = location.invoke(value.invoke(sound.invoke(packet))).toString();
        return SWEEP.equals(key) || legacyHitEffects && MODERN_ATTACK_SOUNDS.contains(key);
    }
}

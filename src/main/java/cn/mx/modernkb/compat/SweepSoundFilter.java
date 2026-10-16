package cn.mx.modernkb.compat;

import java.lang.reflect.Method;

/** Reads immutable packet data only; safe on a connection's event loop. */
final class SweepSoundFilter {
    private static final String SWEEP = "minecraft:entity.player.attack.sweep";
    private final Class<?> positional = Class.forName("net.minecraft.network.protocol.game.ClientboundSoundPacket");
    private final Class<?> attached = Class.forName("net.minecraft.network.protocol.game.ClientboundSoundEntityPacket");
    private final Method positionalSound = positional.getMethod("getSound");
    private final Method attachedSound = attached.getMethod("getSound");
    private final Method value = Class.forName("net.minecraft.core.Holder").getMethod("value");
    private final Method location = Class.forName("net.minecraft.sounds.SoundEvent").getMethod("location");

    SweepSoundFilter() throws ReflectiveOperationException {}

    boolean matches(Object packet) throws ReflectiveOperationException {
        Method sound = positional.isInstance(packet) ? positionalSound : attached.isInstance(packet) ? attachedSound : null;
        if (sound == null) return false;
        // Resolve the identifier, not a numeric registry ID or Holder identity. Direct holders
        // (e.g. plugin sounds) and registered holders must behave identically.
        return SWEEP.equals(location.invoke(value.invoke(sound.invoke(packet))).toString());
    }
}

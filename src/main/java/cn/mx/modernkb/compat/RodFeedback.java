package cn.mx.modernkb.compat;

import cn.mx.modernkb.combat.CombatTracker;
import java.lang.reflect.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.bukkit.SoundCategory;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Player;

/** Client damage notification for an accepted zero-health-damage rod hit. */
@FunctionalInterface
public interface RodFeedback {
    boolean play(Player victim, DamageSource source, BooleanSupplier current);

    /** Public-API fallback for integrations constructing ClassicCombat themselves. */
    static RodFeedback bukkit() {
        return (victim, source, current) -> {
            if (!CombatTracker.owns(victim) || !current.getAsBoolean()) return false;
            victim.playHurtAnimation(-90f);
            if (CombatTracker.owns(victim) && current.getAsBoolean()) {
                var sound = victim.getHurtSound();
                if (sound != null) victim.getWorld().playSound(victim, sound, SoundCategory.PLAYERS, 1, 1);
            }
            return true;
        };
    }

    /** Symbols are resolved at enable, alongside RodVelocity; no second damage/health call. */
    static RodFeedback forServer() throws ReflectiveOperationException {
        Class<?> entity = Class.forName("net.minecraft.world.entity.Entity");
        Class<?> nativePlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
        Class<?> nativeSource = Class.forName("net.minecraft.world.damagesource.DamageSource");
        Class<?> packet = Class.forName("net.minecraft.network.protocol.Packet");
        Method handle = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer").getMethod("getHandle");
        Method sourceHandle = Class.forName("org.bukkit.craftbukkit.damage.CraftDamageSource").getMethod("getHandle");
        Method tracked = entity.getMethod("moonrise$getTrackedEntity");
        Method broadcast = tracked.getReturnType().getMethod("sendToTrackingPlayersAndSelf", packet);
        Field connection = nativePlayer.getField("connection");
        Method send = connection.getType().getMethod("send", packet);
        Constructor<?> damage = Class.forName("net.minecraft.network.protocol.game.ClientboundDamageEventPacket")
                .getConstructor(entity, nativeSource);
        Constructor<?> hurt = Class.forName("net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket")
                .getConstructor(int.class, float.class);
        Constructor<?> bundle = Class.forName("net.minecraft.network.protocol.game.ClientboundBundlePacket")
                .getConstructor(Iterable.class);
        return (victim, source, current) -> {
            if (!CombatTracker.owns(victim) || !current.getAsBoolean()) return false;
            try {
                Object player = handle.invoke(victim);
                // The native damage notification starts hurt state AND the client hurt sound.
                // Keep the fixed classic tilt in the same bundle. Motion was sent beforehand.
                Object message = bundle.newInstance(List.of(damage.newInstance(player, sourceHandle.invoke(source)),
                        hurt.newInstance(victim.getEntityId(), 0f)));
                Object tracking = tracked.invoke(player);
                if (tracking != null) broadcast.invoke(tracking, message);
                else send.invoke(connection.get(player), message);
                return true;
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Cannot send classic rod damage feedback", ex);
            }
        };
    }
}

package cn.mx.modernkb.compat;

import cn.mx.modernkb.combat.CombatTracker;
import java.lang.reflect.*;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.Vector;

/** Flush one accepted rod velocity on the victim region, before sending the hurt animation. */
@FunctionalInterface
public interface RodVelocity {
    boolean deliver(Player player, Vector before, BooleanSupplier current);

    /** Compatibility for callers that use ClassicCombat independently of the plugin bootstrap. */
    static RodVelocity deferred() {
        return (player, before, current) -> {
            if (!CombatTracker.owns(player) || !current.getAsBoolean()) return false;
            player.setVelocity(player.getVelocity());
            return true;
        };
    }

    interface Transport {
        void send(Player player, Vector velocity);
        void discard(Player player, Vector before);
    }

    static RodVelocity immediate(Transport transport) {
        return (player, before, current) -> {
            if (!CombatTracker.owns(player) || !current.getAsBoolean()) return false;
            var event = new PlayerVelocityEvent(player, player.getVelocity().clone());
            Bukkit.getPluginManager().callEvent(event);
            // A nested hit or mode change owns its new velocity. Never overwrite or clear it.
            if (!CombatTracker.owns(player) || !current.getAsBoolean()) return false;
            if (event.isCancelled()) {
                transport.discard(player, before);
                return false;
            }
            event.getVelocity().checkFinite();
            transport.send(player, event.getVelocity());
            return true;
        };
    }

    /** Symbols are resolved at enable; the plugin is already restricted to 1.21.11. */
    static RodVelocity forServer() throws ReflectiveOperationException {
        Class<?> entity = Class.forName("net.minecraft.world.entity.Entity");
        Class<?> player = Class.forName("net.minecraft.server.level.ServerPlayer");
        Class<?> packet = Class.forName("net.minecraft.network.protocol.Packet");
        Method handle = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer").getMethod("getHandle");
        Method tracked = entity.getMethod("moonrise$getTrackedEntity");
        Method broadcast = tracked.getReturnType().getMethod("sendToTrackingPlayersAndSelf", packet);
        Field connection = player.getField("connection");
        Method send = connection.getType().getMethod("send", packet);
        Field dirty = entity.getField("hurtMarked");
        Constructor<?> motion = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket")
                .getConstructor(entity);
        return immediate(new Transport() {
            @Override public void send(Player target, Vector velocity) {
                try {
                    Object nativePlayer = handle.invoke(target);
                    target.setVelocity(velocity);
                    Object message = motion.newInstance(nativePlayer);
                    Object tracking = tracked.invoke(nativePlayer);
                    // Consume this notification before packet callbacks can install another hit
                    // or move the player to another region. Never clear that newer notification.
                    dirty.setBoolean(nativePlayer, false);
                    if (tracking != null) broadcast.invoke(tracking, message);
                    else send.invoke(connection.get(nativePlayer), message);
                } catch (ReflectiveOperationException ex) { throw new IllegalStateException("Cannot flush classic rod velocity", ex); }
            }
            @Override public void discard(Player target, Vector before) {
                try {
                    target.setVelocity(before);
                    dirty.setBoolean(handle.invoke(target), false);
                } catch (ReflectiveOperationException ex) { throw new IllegalStateException("Cannot discard cancelled classic rod velocity", ex); }
            }
        });
    }
}

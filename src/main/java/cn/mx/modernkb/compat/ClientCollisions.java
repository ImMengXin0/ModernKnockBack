package cn.mx.modernkb.compat;

import cn.mx.modernkb.combat.CombatTracker;
import cn.mx.modernkb.config.*;
import io.netty.channel.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.Plugin;

/** Classic client rules: disable predicted pushing and hide modern sweep sound/particles. */
public final class ClientCollisions implements Listener, AutoCloseable {
    private static final String HANDLER = "modernkb_collision";
    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final PlayerStore players;
    private final TeamPacketCodec codec;
    private final Map<UUID, Overlay> connections = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public ClientCollisions(Plugin plugin, Supplier<Settings> settings, PlayerStore players) throws ReflectiveOperationException {
        this.plugin = plugin; this.settings = settings; this.players = players; codec = new TeamPacketCodec();
    }
    private boolean active(UUID id) { return !closed && settings.get().classicEnabled() && !players.get(id).excluded(); }
    public void reconcile(Player player) {
        if (closed || !CombatTracker.owns(player)) return;
        UUID id = player.getUniqueId();
        Overlay existing = connections.get(id);
        if (existing != null) { existing.refresh(); return; }
        try {
            Channel channel = codec.channel(player);
            var state = new CollisionTeams<Object>(player.getName(), "mkb" + UUID.randomUUID().toString().replace("-", "").substring(0, 13),
                    codec.noCollision(null), codec::noCollision);
            for (Object message : codec.snapshot(player)) state.accept(codec.decode(message));
            var overlay = new Overlay(channel, state, codec, () -> active(id), message -> plugin.getLogger().warning(message));
            if (connections.putIfAbsent(id, overlay) != null) return;
            channel.eventLoop().execute(() -> {
                if (closed || connections.get(id) != overlay || !channel.isActive()) { connections.remove(id, overlay); return; }
                channel.pipeline().addBefore("packet_handler", HANDLER, overlay);
                overlay.update(channel.pipeline().context(overlay));
            });
        } catch (ReflectiveOperationException ex) { throw new IllegalStateException("Cannot install classic client collision rules", ex); }
    }
    public void refreshAll() {
        connections.values().forEach(Overlay::refresh);
        for (Player player : Bukkit.getOnlinePlayers()) player.getScheduler().run(plugin, task -> reconcile(player), null);
    }
    @EventHandler(priority = EventPriority.LOWEST) public void join(PlayerJoinEvent event) { reconcile(event.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent event) {
        Overlay overlay = connections.remove(event.getPlayer().getUniqueId());
        if (overlay != null) overlay.remove();
    }
    @Override public void close() {
        closed = true;
        connections.values().forEach(Overlay::remove); connections.clear();
    }
    static final class Overlay extends ChannelDuplexHandler {
        private final Channel channel;
        private final CollisionTeams<Object> state;
        private final TeamPacketCodec codec;
        private final BooleanSupplier active;
        private final Consumer<String> warning;
        Overlay(Channel channel, CollisionTeams<Object> state, TeamPacketCodec codec, BooleanSupplier active, Consumer<String> warning) {
            this.channel = channel; this.state = state; this.codec = codec; this.active = active; this.warning = warning;
        }
        void update(ChannelHandlerContext context) { update(context, active.getAsBoolean()); }
        private void update(ChannelHandlerContext context, boolean enabled) {
            if (context == null || !channel.isActive()) return;
            try {
                var messages = state.enabled(enabled);
                for (var message : messages) context.write(codec.encode(message));
                if (!messages.isEmpty()) context.flush();
            } catch (ReflectiveOperationException ex) { throw new IllegalStateException("Cannot update collision overlay", ex); }
        }
        void refresh() { channel.eventLoop().execute(() -> update(channel.pipeline().context(this))); }
        void remove() {
            channel.eventLoop().execute(() -> {
                ChannelHandlerContext context = channel.pipeline().context(this);
                if (context == null) return;
                try {
                    if (channel.isActive()) {
                        for (var message : state.enabled(false)) context.write(codec.encode(message));
                        context.flush();
                    }
                } catch (ReflectiveOperationException ex) { warning.accept("Could not restore client collision rules: " + ex); }
                finally { channel.pipeline().remove(this); }
            });
        }
        @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            boolean enabled = active.getAsBoolean();
            update(context, enabled);
            var output = codec.transform(message, state, enabled);
            if (output.isEmpty()) {
                // Dropped packets still need a completed write promise; otherwise the
                // connection's completion listeners can wait forever for a hidden effect.
                io.netty.util.ReferenceCountUtil.release(message);
                promise.trySuccess();
                return;
            }
            for (int index = 0; index < output.size(); index++)
                context.write(output.get(index), index == output.size() - 1 ? promise : context.voidPromise());
        }
    }
}

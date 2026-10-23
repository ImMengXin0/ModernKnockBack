package cn.mx.modernkb.compat;

import io.netty.channel.Channel;
import java.lang.reflect.*;
import java.util.*;
import org.bukkit.entity.Player;

/** Isolated 1.21.11 team/effect adapter; never mutates a shared packet or a live scoreboard. */
final class TeamPacketCodec {
    private final Class<?> packet = Class.forName("net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket");
    private final Class<?> parameters = Class.forName(packet.getName() + "$Parameters");
    private final Class<?> team = Class.forName("net.minecraft.world.scores.PlayerTeam");
    private final Class<?> board = Class.forName("net.minecraft.world.scores.Scoreboard");
    private final Class<?> bundle = Class.forName("net.minecraft.network.protocol.game.ClientboundBundlePacket");
    private final Constructor<?> packetConstructor = packet.getDeclaredConstructor(String.class, int.class, Optional.class, Collection.class);
    private final Constructor<?> teamConstructor = team.getConstructor(board, String.class);
    private final Constructor<?> boardConstructor = board.getConstructor();
    private final Constructor<?> parametersConstructor = parameters.getConstructor(team);
    private final Constructor<?> bundleConstructor = bundle.getConstructor(Iterable.class);
    private final Field method = packet.getDeclaredField("method");
    private final Method name = packet.getMethod("getName"), members = packet.getMethod("getPlayers"), params = packet.getMethod("getParameters");
    private final Method children = bundle.getMethod("subPackets");
    private final Method handle = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer").getMethod("getHandle");
    private final Field listener = Class.forName("net.minecraft.server.level.ServerPlayer").getField("connection");
    private final Field connection = listener.getType().getField("connection");
    private final Field channel = connection.getType().getField("channel");
    private final Method boardHandle = Class.forName("org.bukkit.craftbukkit.scoreboard.CraftScoreboard").getMethod("getHandle");
    private final Method teams = board.getMethod("getPlayerTeams");
    private final Method add = packet.getMethod("createAddOrModifyPacket", team, boolean.class);
    private final Method collision = team.getMethod("setCollisionRule", Class.forName("net.minecraft.world.scores.Team$CollisionRule"));
    private final Object never = collision.getParameterTypes()[0].getField("NEVER").get(null);
    private final List<Method[]> copies = new ArrayList<>();
    private final SweepEffectFilter sweepEffects = new SweepEffectFilter();
    private final HurtAnimationFilter hurtAnimation = new HurtAnimationFilter();

    TeamPacketCodec() throws ReflectiveOperationException {
        packetConstructor.setAccessible(true); method.setAccessible(true);
        for (String property : List.of("DisplayName", "PlayerPrefix", "PlayerSuffix", "Color", "NametagVisibility", "Options")) {
            Method getter = parameters.getMethod("get" + property);
            String setter = switch (property) {
                case "NametagVisibility" -> "setNameTagVisibility";
                case "Options" -> "unpackOptions";
                default -> "set" + property;
            };
            copies.add(new Method[]{getter, team.getMethod(setter, getter.getReturnType())});
        }
    }
    Channel channel(Player player) throws ReflectiveOperationException {
        return (Channel) channel.get(connection.get(listener.get(handle.invoke(player))));
    }
    List<Object> snapshot(Player player) throws ReflectiveOperationException {
        var result = new ArrayList<Object>();
        for (Object value : (Collection<?>) teams.invoke(boardHandle.invoke(player.getScoreboard())))
            result.add(add.invoke(null, value, true));
        return result;
    }
    Object noCollision(Object original) {
        try {
            Object temporary = teamConstructor.newInstance(boardConstructor.newInstance(), "mkb_copy");
            if (original != null) for (Method[] pair : copies) pair[1].invoke(temporary, pair[0].invoke(original));
            collision.invoke(temporary, never);
            return parametersConstructor.newInstance(temporary);
        } catch (ReflectiveOperationException ex) { throw new IllegalStateException("Cannot copy team collision parameters", ex); }
    }
    @SuppressWarnings("unchecked")
    CollisionTeams.Packet<Object> decode(Object value) throws ReflectiveOperationException {
        if (!packet.isInstance(value)) return null;
        return new CollisionTeams.Packet<>((String) name.invoke(value), method.getInt(value),
                ((Optional<?>) params.invoke(value)).orElse(null), List.copyOf((Collection<String>) members.invoke(value)));
    }
    Object encode(CollisionTeams.Packet<Object> value) throws ReflectiveOperationException {
        return packetConstructor.newInstance(value.name(), value.method(), Optional.ofNullable(value.parameters()), value.players());
    }
    List<Object> transform(Object value, CollisionTeams<Object> state) throws ReflectiveOperationException {
        return transform(value, state, false);
    }
    List<Object> transform(Object value, CollisionTeams<Object> state, boolean hideSweep) throws ReflectiveOperationException {
        return transform(value, state, hideSweep, -1);
    }
    List<Object> transform(Object value, CollisionTeams<Object> state, boolean classic, int selfEntityId) throws ReflectiveOperationException {
        if (classic && sweepEffects.matches(value)) return List.of();
        if (bundle.isInstance(value)) {
            var output = new ArrayList<Object>();
            for (Object child : (Iterable<?>) children.invoke(value)) output.addAll(transform(child, state, classic, selfEntityId));
            if (classic && output.isEmpty()) return List.of();
            return List.of(bundleConstructor.newInstance(output));
        }
        if (classic) value = hurtAnimation.transform(value, selfEntityId);
        var decoded = decode(value);
        if (decoded == null) return List.of(value);
        var output = new ArrayList<Object>();
        for (var changed : state.accept(decoded)) output.add(encode(changed));
        return output;
    }
}

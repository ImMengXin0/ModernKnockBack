package cn.mx.modernkb.compat;

import java.util.*;
import java.util.function.UnaryOperator;

/** Per-viewer client team overlay. All access belongs to that connection's event loop. */
public final class CollisionTeams<P> {
    public record Packet<P>(String name, int method, P parameters, List<String> players) {}
    private final String viewer, fallback;
    private final P fallbackParameters;
    private final UnaryOperator<P> withoutCollision;
    private final Map<String, P> originals = new LinkedHashMap<>();
    private String ownTeam;
    private boolean enabled;

    public CollisionTeams(String viewer, String fallback, P fallbackParameters, UnaryOperator<P> withoutCollision) {
        this.viewer = viewer; this.fallback = fallback;
        this.fallbackParameters = fallbackParameters; this.withoutCollision = withoutCollision;
    }
    public List<Packet<P>> enabled(boolean value) {
        if (enabled == value) return List.of();
        enabled = value;
        var output = new ArrayList<Packet<P>>();
        if (!value) output.add(new Packet<>(fallback, 1, null, List.of()));
        originals.forEach((name, parameters) -> output.add(new Packet<>(name, 2,
                value ? withoutCollision.apply(parameters) : parameters, List.of())));
        if (value) output.add(new Packet<>(fallback, 0, fallbackParameters,
                ownTeam == null ? List.of(viewer) : List.of()));
        return output;
    }
    public List<Packet<P>> accept(Packet<P> packet) {
        String before = ownTeam;
        switch (packet.method) {
            case 0, 2 -> originals.put(packet.name, packet.parameters);
            case 1 -> originals.remove(packet.name);
            default -> { }
        }
        if (packet.method == 1 && packet.name.equals(ownTeam)
                || packet.method == 4 && packet.name.equals(ownTeam) && packet.players.contains(viewer)) ownTeam = null;
        if ((packet.method == 0 || packet.method == 3) && packet.players.contains(viewer)) ownTeam = packet.name;
        var output = new ArrayList<Packet<P>>();
        output.add(enabled && packet.parameters != null
                ? new Packet<>(packet.name, packet.method, withoutCollision.apply(packet.parameters), packet.players) : packet);
        // Team removal must not leave the client's local player collidable until the next update.
        if (enabled && before != null && ownTeam == null)
            output.add(new Packet<>(fallback, 3, null, List.of(viewer)));
        return output;
    }
}

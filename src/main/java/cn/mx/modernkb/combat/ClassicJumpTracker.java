package cn.mx.modernkb.combat;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/** Confirms a jump from client intent and the movement accepted after Paper's jump event. */
final class ClassicJumpTracker {
    private static final class Jump {
        final int tick;
        final Object configuration;
        final PlayerJumpEvent event;
        final Location from, to;
        boolean input;
        PlayerMoveEvent movement;
        Jump(int tick, PlayerJumpEvent event, boolean input, Object configuration) {
            this.tick = tick;
            this.configuration = configuration;
            this.event = event;
            this.from = event.getFrom().clone();
            this.to = event.getTo().clone();
            this.input = input;
        }
        boolean unchanged() {
            return !event.isCancelled() && position(from, event.getFrom()) && position(to, event.getTo())
                    && (movement == null || !movement.isCancelled()
                        && position(from, movement.getFrom()) && position(to, movement.getTo()));
        }
    }
    private final Map<UUID, Boolean> inputs = new ConcurrentHashMap<>();
    private final Map<UUID, Jump> jumps = new ConcurrentHashMap<>();

    void input(PlayerInputEvent event) {
        Player player = event.getPlayer();
        boolean pressed = event.getInput().isJump();
        inputs.put(player.getUniqueId(), pressed);
        Jump jump = jumps.get(player.getUniqueId());
        // Input and movement may arrive in either order in one tick. A later tick's new
        // jump press must never retroactively turn an earlier knockback lift into a jump.
        if (pressed && jump != null && jump.tick == player.getTicksLived() && jump.unchanged())
            jump.input = true;
    }

    void jump(PlayerJumpEvent event, Object configuration) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Location from = event.getFrom(), to = event.getTo();
        if (event.isCancelled() || from.getWorld() != to.getWorld() || to.getY() <= from.getY()) {
            jumps.remove(id);
            return;
        }
        Boolean pressed = inputs.get(id);
        if (pressed == null) {
            Input current = player.getCurrentInput();
            pressed = current != null && current.isJump();
        }
        jumps.put(id, new Jump(player.getTicksLived(), event, pressed, configuration));
    }

    void move(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Jump jump = jumps.get(id);
        if (jump == null) return;
        if (event.isCancelled()) {
            // An unrelated rejected movement does not erase an already accepted jump.
            if (jump.movement == null) jumps.remove(id, jump);
            return;
        }
        if (player.isOnGround() || !jump.unchanged()) {
            jumps.remove(id, jump);
            return;
        }
        if (jump.movement == null) {
            if (jump.tick != player.getTicksLived()
                    || !position(jump.from, event.getFrom()) || !position(jump.to, event.getTo())) {
                jumps.remove(id, jump);
                return;
            }
            jump.movement = event;
        }
    }

    boolean recent(Player player, int window, Object configuration) {
        UUID id = player.getUniqueId();
        Jump jump = jumps.get(id);
        if (jump == null) return false;
        if (jump.configuration != configuration || !jump.unchanged()
                || !ClassicCombat.within(jump.tick, player.getTicksLived(), window + 1)) {
            jumps.remove(id, jump);
            return false;
        }
        return jump.input && jump.movement != null;
    }

    private static boolean position(Location first, Location second) {
        return second != null && first.getWorld() == second.getWorld()
                && Double.compare(first.getX(), second.getX()) == 0
                && Double.compare(first.getY(), second.getY()) == 0
                && Double.compare(first.getZ(), second.getZ()) == 0;
    }

    void clear(UUID id) { inputs.remove(id); jumps.remove(id); }
    void clear() { inputs.clear(); jumps.clear(); }
}

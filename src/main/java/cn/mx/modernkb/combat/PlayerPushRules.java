package cn.mx.modernkb.combat;

import cn.mx.modernkb.config.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Suppress only player body pushing. Keep collidable/isPickable intact for projectiles. */
public final class PlayerPushRules implements Listener, AutoCloseable {
    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final PlayerStore players;
    private final NamespacedKey ownedKey, legacyKey;
    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public PlayerPushRules(Plugin plugin, Supplier<Settings> settings, PlayerStore players) {
        this.plugin = plugin; this.settings = settings; this.players = players;
        ownedKey = new NamespacedKey(plugin, "classic_push_exemptions");
        legacyKey = new NamespacedKey(plugin, "classic_previous_collision");
        for (Player player : Bukkit.getOnlinePlayers()) online.add(player.getUniqueId());
    }
    public void reconcile(Player player) {
        if (!CombatTracker.owns(player)) return;
        restore(player);
        if (closed || !online.contains(player.getUniqueId()) || !settings.get().classicEnabled()
                || players.get(player.getUniqueId()).excluded() || !player.isCollidable()) return;
        // With collidable=true, exemptions disable pushing against these UUIDs only.
        // They do not participate in Entity.canBeHitByProjectile/isPickable.
        Set<UUID> added = new HashSet<>(online);
        added.remove(player.getUniqueId());
        added.removeAll(player.getCollidableExemptions());
        if (added.isEmpty()) return;
        // Persist ownership so a Folia disable/crash can be recovered on next enable/join.
        String marker = String.join(",", added.stream().map(UUID::toString).sorted().toList());
        player.getPersistentDataContainer().set(ownedKey, PersistentDataType.STRING, marker);
        player.getCollidableExemptions().addAll(added);
    }
    public void restore(Player player) {
        if (!CombatTracker.owns(player)) return;
        var data = player.getPersistentDataContainer();
        Integer legacy = data.get(legacyKey, PersistentDataType.INTEGER);
        if (legacy != null) {
            // Undo 1.2.8's broad collision flag; never enable a player originally disabled by another plugin.
            if (!player.isCollidable()) player.setCollidable(legacy != 0);
            data.remove(legacyKey);
        }
        String marker = data.get(ownedKey, PersistentDataType.STRING);
        if (marker == null) return;
        for (String value : marker.split(",")) {
            try { player.getCollidableExemptions().remove(UUID.fromString(value)); }
            catch (IllegalArgumentException ignored) { /* Ignore a malformed ownership entry. */ }
        }
        data.remove(ownedKey);
    }
    public void refreshAll() {
        for (UUID id : online) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.getScheduler().run(plugin, task -> reconcile(player), null);
        }
    }
    @EventHandler(priority = EventPriority.LOWEST) public void join(PlayerJoinEvent event) {
        online.add(event.getPlayer().getUniqueId());
        reconcile(event.getPlayer()); refreshAll();
    }
    @EventHandler public void respawn(PlayerRespawnEvent event) {
        event.getPlayer().getScheduler().run(plugin, task -> reconcile(event.getPlayer()), null);
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        online.remove(event.getPlayer().getUniqueId());
        restore(event.getPlayer()); refreshAll();
    }
    @Override public void close() {
        closed = true;
        for (Player player : Bukkit.getOnlinePlayers()) restore(player);
        online.clear();
    }
}

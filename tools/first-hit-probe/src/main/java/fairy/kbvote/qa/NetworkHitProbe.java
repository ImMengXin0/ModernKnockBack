package fairy.kbvote.qa;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/** Real client attacks. Tick time is not network latency; the client measures its own round trip. */
final class NetworkHitProbe implements Listener {
    private final Plugin plugin;
    private Player attacker, victim;
    private int sample;
    private String cycle;
    private long preNanos, velocityNanos;
    private Vector eventVelocity;
    private boolean armed;

    NetworkHitProbe(Plugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }
    boolean start(Player attacker, Player victim, String cycle) {
        if (this.attacker != null) return false;
        this.attacker = attacker; this.victim = victim; this.cycle = cycle;
        sample = 0;
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.IRON_SWORD));
        victim.getInventory().setArmorContents(new ItemStack[4]);
        prepare();
        return true;
    }
    private void prepare() {
        preNanos = velocityNanos = 0; eventVelocity = null; armed = false;
        if (!attacker.isOnline() || !victim.isOnline()) { fail("Disconnected"); return; }
        attacker.teleport(new Location(attacker.getWorld(), .5, 91, .5, -90, 0));
        victim.teleport(new Location(victim.getWorld(), 2.5, 91, .5));
        victim.setNoDamageTicks(0); victim.setLastDamage(0); victim.setHealth(20);
        victim.setVelocity(new Vector());
        int expected = ++sample;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (attacker == null || sample != expected) return;
            armed = true;
            attacker.sendMessage("FIRST_HIT_NET_READY n=" + sample + " target=" + victim.getEntityId());
        }, 30);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (attacker != null && sample == expected && armed) fail("No accepted client attack");
        }, 130);
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void pre(PrePlayerAttackEntityEvent e) {
        // ModernKB's LOWEST listener was registered first. This interval deliberately does not
        // claim to include that listener, the full native call, or inbound network scheduling.
        if (armed && e.getPlayer() == attacker && e.getAttacked() == victim && preNanos == 0)
            preNanos = System.nanoTime();
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void velocity(PlayerVelocityEvent e) {
        if (armed && preNanos != 0 && e.getPlayer() == victim) {
            velocityNanos = System.nanoTime(); eventVelocity = e.getVelocity().clone();
        }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void tick(ServerTickEndEvent e) {
        if (!armed || preNanos == 0) return;
        armed = false;
        double damage = 20 - victim.getHealth();
        String message = "FIRST_HIT_NET_RESULT cycle=" + cycle + " n=" + sample
                + " observedPreToVelocityMs=" + (velocityNanos == 0 ? -1 : (velocityNanos - preNanos) / 1e6)
                + " serverTickMs=" + e.getTickDuration() + " damage=" + damage + " velocityEvent=" + eventVelocity;
        plugin.getLogger().info(message); attacker.sendMessage(message);
        if (damage <= 0 || velocityNanos == 0) { fail("Missing damage or velocity event"); return; }
        if (sample < 6) Bukkit.getScheduler().runTaskLater(plugin, this::prepare, 10);
        else {
            Player recipient = attacker;
            attacker = victim = null;
            Bukkit.getScheduler().runTaskLater(plugin, () -> recipient.sendMessage("FIRST_HIT_COMPLETE"), 10);
        }
    }
    private void fail(String message) {
        plugin.getLogger().severe("FIRST_HIT_FAIL " + message);
        if (attacker != null) attacker.sendMessage("FIRST_HIT_FAIL " + message);
        attacker = victim = null; armed = false;
    }
}

package fairy.kbvote.qa;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import java.util.*;

/** Test-only. Standalone Paper + ModernKnockBack; never install on a production server. */
public final class FirstHitQA extends JavaPlugin implements Listener {
    private final Map<UUID, Vector> velocity = new HashMap<>();
    private boolean active;
    @Override public void onEnable() {
        if (!getServer().getIp().equals("127.0.0.1") || getServer().getPort() != 27183) {
            getLogger().severe("Requires dedicated loopback port 27183");
            getServer().getPluginManager().disablePlugin(this); return;
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        World world = Bukkit.getWorlds().getFirst();
        for (int x = -5; x <= 10; x++) for (int z = -5; z <= 5; z++)
            world.getBlockAt(x, 90, z).setType(Material.STONE);
        getLogger().info("FIRST_HIT_READY");
    }
    @EventHandler public void join(PlayerJoinEvent e) {
        e.getPlayer().setGameMode(GameMode.SURVIVAL);
        e.getPlayer().teleport(new Location(Bukkit.getWorlds().getFirst(), .5, 91, .5));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void velocity(PlayerVelocityEvent e) { velocity.put(e.getPlayer().getUniqueId(), e.getVelocity().clone()); }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p) || !p.getName().startsWith("KBQA") || args.length == 0) return true;
        if (args[0].equals("stop")) { p.sendMessage("FIRST_HIT_STOPPING"); Bukkit.getScheduler().runTaskLater(this, Bukkit::shutdown, 10); return true; }
        if (!args[0].equals("bench") || args.length < 2 || active) return true;
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null || target == p) return true;
        active = true;
        p.getInventory().setItemInMainHand(new ItemStack(Material.IRON_SWORD));
        target.getInventory().setArmorContents(new ItemStack[4]);
        boolean instrument = args.length > 2 && args[2].equals("instrument");
        FirstHitProbe probe = instrument ? new FirstHitProbe() : null;
        new BukkitRunnable() {
            int step, hits;
            @Override public void run() {
                try {
                    if ((step++ & 1) == 0) {
                        p.teleport(new Location(p.getWorld(), .5, 91, .5, -90, 0));
                        target.teleport(new Location(p.getWorld(), 2.5, 91, .5));
                        target.setNoDamageTicks(0); target.setLastDamage(0); target.setHealth(20);
                        target.setVelocity(new Vector()); velocity.remove(target.getUniqueId()); return;
                    }
                    double before = target.getHealth();
                    if (probe != null) probe.begin();
                    long start = System.nanoTime(); p.attack(target); double ms = (System.nanoTime() - start) / 1e6;
                    String handlers = probe == null ? "disabled" : probe.finish();
                    getLogger().info("FIRST_HIT_SAMPLE n=" + (++hits) + " attackMs=" + ms
                            + " damage=" + (before - target.getHealth()) + " sent=" + velocity.get(target.getUniqueId())
                            + " handlers=" + handlers);
                    if (target.getHealth() >= before || velocity.get(target.getUniqueId()) == null)
                        throw new IllegalStateException("Missing damage or velocity event");
                    if (hits == 6) { finish(); p.sendMessage("FIRST_HIT_COMPLETE"); }
                } catch (Throwable ex) { finish(); getLogger().log(java.util.logging.Level.SEVERE, "FIRST_HIT_FAIL", ex); p.sendMessage("FIRST_HIT_FAIL " + ex.getMessage()); }
            }
            private void finish() { cancel(); active = false; if (probe != null) probe.close(); }
        }.runTaskTimer(this, 20, 15);
        return true;
    }
}

package cn.mx.modernkb.compat;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.*;
import org.bukkit.event.server.*;
import org.bukkit.plugin.Plugin;

public final class SyncCompatibility implements Listener {
    private volatile boolean active;
    private volatile String status = "not installed";
    public void refresh() {
        Plugin sync = Bukkit.getPluginManager().getPlugin("KnockbackSync");
        active = sync != null && sync.isEnabled();
        status = sync == null ? "not installed" : sync.getPluginMeta().getVersion() + (active ? " enabled" : " disabled");
        if (sync != null && foliaRuntime() && !declaresFolia(sync)) status += "; WARNING: this KnockbackSync build does not declare Folia support";
    }
    private static boolean declaresFolia(Plugin plugin) {
        try (InputStream in = plugin.getResource("plugin.yml")) {
            if (in == null) return false;
            var yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml.getBoolean("folia-supported", false);
        } catch (Exception ex) { return false; }
    }
    public static boolean foliaRuntime() {
        try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer"); return true; }
        catch (ClassNotFoundException ex) { return false; }
    }
    public boolean active() { return active; }
    public String status() { return status; }
    @EventHandler public void enabled(PluginEnableEvent event) { if (event.getPlugin().getName().equals("KnockbackSync")) refresh(); }
    @EventHandler public void disabled(PluginDisableEvent event) {
        if (event.getPlugin().getName().equals("KnockbackSync")) { active = false; status = "disabled"; }
    }
}

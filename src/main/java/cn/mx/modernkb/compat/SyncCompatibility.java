package cn.mx.modernkb.compat;

import java.io.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.*;
import org.bukkit.event.server.*;
import org.bukkit.plugin.Plugin;

public final class SyncCompatibility implements Listener {
    // Only b142's verified Bukkit internals are used; other builds keep the existing integration.
    private static final String B142 = "1.3.6-dev-b142";
    private static final String KBS = "me.caseload.knockbacksync.";
    private record State(boolean loaded, String version, String warning, MethodHandle toggle) {
        boolean active() {
            if (toggle == null) return loaded;
            try { return (boolean) toggle.invokeExact(); }
            catch (Throwable ex) {
                if (ex instanceof Error fatal && !(fatal instanceof LinkageError)) throw fatal;
                return loaded;
            }
        }
    }
    private volatile State state = new State(false, "not installed", "", null);
    public void refresh() {
        Plugin sync = Bukkit.getPluginManager().getPlugin("KnockbackSync");
        if (sync == null) { state = new State(false, "not installed", "", null); return; }
        String version = sync.getPluginMeta().getVersion();
        String warning = foliaRuntime() && !declaresFolia(sync) ? "; WARNING: this KnockbackSync build does not declare Folia support" : "";
        MethodHandle toggle = null;
        if (sync.isEnabled() && B142.equals(version)) {
            try {
                Class<?> base = Class.forName(KBS + "Base", false, sync.getClass().getClassLoader());
                Object manager = base.getMethod("getConfigManager").invoke(base.getField("INSTANCE").get(null));
                toggle = MethodHandles.publicLookup().unreflect(manager.getClass().getMethod("isToggled")).bindTo(manager);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                sync.getLogger().warning("ModernKnockBack could not read the KBS runtime toggle: " + e);
            }
        }
        State bound = new State(sync.isEnabled(), version, warning, toggle);
        state = bound;
        if (toggle != null && bound.active()) warmBlockMapping(sync);
    }
    // KBS maps a ray hit's BlockData synchronously inside PlayerVelocityEvent. The shaded
    // mapping is lazy per protocol version. Exercise precisely that lookup before combat;
    // no entity, world, chunk, damage, velocity or network call is involved.
    private static void warmBlockMapping(Plugin sync) {
        long start = System.nanoTime();
        try {
            ClassLoader loader = sync.getClass().getClassLoader();
            Class<?> conversion = Class.forName(KBS + "shaded.io.github.retrooper.packetevents.util.SpigotConversionUtil", false, loader);
            Object mapped = conversion.getMethod("fromBukkitBlockData", BlockData.class)
                    .invoke(null, Bukkit.createBlockData(Material.STONE));
            if (mapped == null) throw new IllegalStateException("empty mapping");
            sync.getLogger().info("ModernKnockBack prepared KBS block-state mapping in " + ((System.nanoTime() - start) / 1_000_000) + " ms");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Throwable cause = e instanceof InvocationTargetException wrapper ? wrapper.getCause() : e;
            if (cause instanceof VirtualMachineError fatal) throw fatal;
            sync.getLogger().warning("ModernKnockBack skipped optional KBS block-state warmup: " + cause);
        }
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
    public boolean active() { return state.active(); }
    public String status() {
        State s = state;
        return s.version + (s.loaded ? (s.active() ? " enabled" : " loaded, sync OFF") :
                (s.version.equals("not installed") ? "" : " disabled")) + s.warning;
    }
    @EventHandler public void enabled(PluginEnableEvent event) { if (event.getPlugin().getName().equals("KnockbackSync")) refresh(); }
    @EventHandler public void disabled(PluginDisableEvent event) {
        if (event.getPlugin().getName().equals("KnockbackSync")) state = new State(false, event.getPlugin().getPluginMeta().getVersion(), "", null);
    }
}

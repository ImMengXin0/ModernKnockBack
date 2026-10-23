package cn.mx.modernkb;

import cn.mx.modernkb.api.*;
import cn.mx.modernkb.combat.*;
import cn.mx.modernkb.compat.SyncCompatibility;
import cn.mx.modernkb.compat.RodVelocity;
import cn.mx.modernkb.compat.RodCollision;
import cn.mx.modernkb.compat.ClientCollisions;
import cn.mx.modernkb.config.*;
import cn.mx.modernkb.rod.RodListener;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class ModernKnockbackPlugin extends JavaPlugin implements ModernKnockbackAPI {
    private volatile Settings settings;
    private PlayerStore players;
    private CombatTracker tracker;
    private KnockbackListener knockback;
    private RodListener rods;
    private SyncCompatibility sync;
    private ModeFiles modes;
    private ClassicCombat classic;
    private PlayerPushRules pushing;
    private ClientCollisions collisions;
    private final AtomicBoolean updating = new AtomicBoolean();
    private final Object configurationLock = new Object();

    @Override public void onEnable() {
        try {
            if (!Bukkit.getMinecraftVersion().equals("1.21.11")) throw new IllegalStateException("This build requires Minecraft 1.21.11 / Java 21+");
            if (Bukkit.getPluginManager().getPlugin("KnockbackManager") != null)
                throw new IllegalStateException("Remove the legacy KnockbackManager from this server to avoid double modification");
            if (DataDirectory.migrate(getDataPath().resolveSibling("ModernKnockback"), getDataPath()))
                getLogger().info("Preserved existing configuration in " + getDataPath());
            modes = new ModeFiles(getDataPath(), message -> getLogger().info(message));
            modes.initialize();
            settings = modes.load(modes.selected());
            players = new PlayerStore(getDataPath().resolve("players.yml"));
            validateAssignments(settings);
            tracker = new CombatTracker(this, () -> settings);
            sync = new SyncCompatibility(); sync.refresh();
            classic = new ClassicCombat(this, () -> settings, players, tracker, RodVelocity.forServer());
            pushing = new PlayerPushRules(this, () -> settings, players);
            collisions = new ClientCollisions(this, () -> settings, players);
            knockback = new KnockbackListener(() -> settings, players, tracker, sync::active, classic);
            rods = new RodListener(this, () -> settings, players, tracker, classic, RodCollision.forServer());
            for (var listener : List.of(tracker, sync, pushing, collisions, classic, knockback, rods)) Bukkit.getPluginManager().registerEvents(listener, this);
            Bukkit.getServicesManager().register(ModernKnockbackAPI.class, this, this, ServicePriority.Normal);
            Objects.requireNonNull(getCommand("modernkb")).setExecutor(this);
            getCommand("modernkb").setTabCompleter(this);
            Objects.requireNonNull(getCommand("kbtest")).setExecutor(this);
            getCommand("kbtest").setTabCompleter(this);
            classic.refreshAll();
            pushing.refreshAll();
            collisions.refreshAll();
            getLogger().info("Loaded mode=" + settings.mode().key() + " from " + getDataPath().resolve(settings.mode().key())
                    + "; " + settings.profiles().size() + " profiles. KnockbackSync: " + sync.status());
        } catch (Exception ex) {
            getLogger().log(java.util.logging.Level.SEVERE, "ModernKnockBack could not enable; invalid configuration was not replaced with defaults", ex);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }
    private void validateAssignments(Settings candidate) {
        for (String profile : players.profileNames()) if (!candidate.profiles().containsKey(profile))
            throw new IllegalArgumentException("players.yml references missing profile: " + profile);
    }
    @Override public void onDisable() {
        Bukkit.getServicesManager().unregisterAll(this);
        HandlerList.unregisterAll(this);
        Bukkit.getAsyncScheduler().cancelTasks(this);
        if (rods != null) rods.close();
        if (classic != null) classic.close();
        if (pushing != null) pushing.close();
        if (collisions != null) collisions.close();
        if (tracker != null) tracker.close();
    }
    @Override public Set<String> profiles() { return settings.profiles().keySet(); }
    @Override public CombatMode getMode() { return settings.mode(); }
    @Override public void setMode(CombatMode mode) {
        synchronized (configurationLock) {
            Settings candidate = modes.load(Objects.requireNonNull(mode));
            validateAssignments(candidate);
            try { modes.save(mode); }
            catch (IOException ex) { throw new IllegalStateException("Mode NOT saved; previous mode retained", ex); }
            settings = candidate;
            refreshCombat();
        }
    }
    private void refreshCombat() {
        tracker.clear();
        rods.reset();
        classic.refreshAll();
        pushing.refreshAll();
        collisions.refreshAll();
    }
    @Override public String getProfile(UUID player) { return players.get(player).profile(); }
    @Override public boolean isExcluded(UUID player) { return players.get(player).excluded(); }
    @Override public void setProfile(UUID player, String profile) {
        synchronized (configurationLock) {
            if (!settings.profiles().containsKey(profile)) throw new IllegalArgumentException("Unknown profile: " + profile);
            players.profile(Objects.requireNonNull(player), profile);
        }
    }
    @Override public void setExcluded(UUID player, boolean excluded) {
        players.excluded(Objects.requireNonNull(player), excluded);
        Player online = Bukkit.getPlayer(player);
        if (online != null) online.getScheduler().run(this, task -> {
            classic.reconcile(online); pushing.reconcile(online); collisions.reconcile(online);
        }, null);
    }
    @Override public void setBot(Entity entity, Boolean bot) { tracker.setBot(entity, bot); }
    @Override public void withHitType(LivingEntity attacker, LivingEntity victim, HitType type, Runnable action) { tracker.withType(attacker, victim, type, action); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("modernkb.admin")) { sender.sendMessage("No permission: modernkb.admin"); return true; }
        try {
            if (command.getName().equalsIgnoreCase("kbtest")) {
                if (args.length == 1 && (args[0].equalsIgnoreCase("classic") || args[0].equalsIgnoreCase("modern"))) {
                    CombatMode mode = CombatMode.parse(args[0]);
                    update(sender, () -> setMode(mode));
                } else if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                    update(sender, this::reloadMode);
                } else sender.sendMessage("/kbtest classic|modern|reload | 当前=" + settings.mode().key());
                return true;
            }
            if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
                sender.sendMessage("ModernKnockBack " + getPluginMeta().getVersion() + " | " + Bukkit.getName() + " " + Bukkit.getMinecraftVersion()
                        + " | enabled=" + settings.enabled() + " | regionized=" + SyncCompatibility.foliaRuntime());
                sender.sendMessage("Mode=" + settings.mode().key() + " | config=" + getDataPath().resolve(settings.mode().key()));
                sender.sendMessage("Profiles=" + profiles().size() + " | processed impulses=" + knockback.handled());
                sender.sendMessage("Native formula=" + knockback.nativeHandled() + " | impulse fallback=" + knockback.nativeFallbacks()
                        + " | formula mismatches=" + knockback.nativeMismatches());
                sender.sendMessage("KnockbackSync: " + sync.status() + " | policy=" + (settings.preserveSyncY() ? "preserve-y" : "before-sync"));
                sender.sendMessage("Classic vectors=" + classic.applied() + " | blocked early hits=" + classic.preventedHits()
                        + " | deduplicated KB components=" + classic.preventedComponents());
                if (settings.classicEnabled()) sender.sendMessage("Classic: no attack charge | hit interval=" + settings.classic().hitIntervalTicks()
                        + " ticks | rod interval=" + settings.classic().effectiveRodHitTicks()
                        + " ticks | rod to sword=" + settings.classic().effectiveRodMeleeTicks() + " ticks | velocity protection=" + settings.classic().protectVelocityFromSync()
                        + " | stop_sprint=" + settings.classic().stopSprint() + " | jump reset=" + settings.classic().jumpResetEnabled()
                        + "/" + settings.classic().jumpResetWindowTicks() + " ticks | targeting=native | rod margin=0.3 | player pushing=off");
            } else if (args[0].equalsIgnoreCase("list") && args.length == 1) {
                sender.sendMessage(String.join(", ", new TreeSet<>(profiles())));
            } else if (args[0].equalsIgnoreCase("view") && args.length == 2) {
                var profile = settings.profiles().get(args[1]);
                if (profile == null) throw new IllegalArgumentException("Unknown profile: " + args[1]);
                sender.sendMessage(args[1] + ": " + profile);
            } else if (args[0].equalsIgnoreCase("reload") && args.length == 1) {
                update(sender, this::reloadMode);
            } else if (args[0].equalsIgnoreCase("get") && args.length == 2) {
                UUID id = resolve(args[1]);
                sender.sendMessage(id + " profile=" + getProfile(id) + " excluded=" + isExcluded(id));
            } else if (args[0].equalsIgnoreCase("setprofile") && args.length == 3) {
                UUID id = resolve(args[1]); String profile = args[2];
                update(sender, () -> setProfile(id, profile));
            } else if (args[0].equalsIgnoreCase("exclude") && args.length == 3) {
                UUID id = resolve(args[1]);
                if (!args[2].equalsIgnoreCase("true") && !args[2].equalsIgnoreCase("false")) throw new IllegalArgumentException("Use true or false");
                boolean value = Boolean.parseBoolean(args[2]);
                update(sender, () -> setExcluded(id, value));
            } else sender.sendMessage("/mkb status|list|view <profile>|reload|get <player/uuid>|setprofile <player/uuid> <profile>|exclude <player/uuid> <true/false>");
        } catch (IllegalArgumentException ex) { sender.sendMessage("ModernKnockBack: " + ex.getMessage()); }
        return true;
    }
    private void reloadMode() {
        synchronized (configurationLock) {
            Settings candidate = modes.load(settings.mode());
            validateAssignments(candidate);
            settings = candidate;
            refreshCombat();
        }
    }
    private UUID resolve(String input) {
        try { return UUID.fromString(input); }
        catch (IllegalArgumentException ignored) {
            Player player = Bukkit.getPlayerExact(input);
            if (player == null) throw new IllegalArgumentException("Player must be online, or specify a UUID");
            return player.getUniqueId();
        }
    }
    private void update(CommandSender sender, Runnable action) {
        if (!updating.compareAndSet(false, true)) { sender.sendMessage("Another configuration operation is still running"); return; }
        try {
            Bukkit.getAsyncScheduler().runNow(this, task -> {
                String result;
                try { action.run(); result = "ModernKnockBack: saved/applied successfully | mode=" + settings.mode().key(); }
                catch (RuntimeException ex) {
                    result = "ModernKnockBack: failed; previous state retained. " + ex.getMessage();
                    getLogger().log(java.util.logging.Level.WARNING, result, ex);
                } finally { updating.set(false); }
                String message = result;
                if (sender instanceof Entity entity) entity.getScheduler().run(this, t -> sender.sendMessage(message), null);
                else Bukkit.getGlobalRegionScheduler().run(this, t -> sender.sendMessage(message));
            });
        } catch (RuntimeException ex) { updating.set(false); throw ex; }
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("modernkb.admin")) return List.of();
        if (command.getName().equalsIgnoreCase("kbtest")) {
            String prefix = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
            return args.length <= 1 ? List.of("classic", "modern", "reload").stream().filter(s -> s.startsWith(prefix)).toList() : List.of();
        }
        Collection<String> choices = args.length == 1 ? List.of("status", "reload", "list", "view", "get", "setprofile", "exclude")
                : args.length == 2 && args[0].equalsIgnoreCase("view") || args.length == 3 && args[0].equalsIgnoreCase("setprofile") ? profiles()
                : args.length == 3 && args[0].equalsIgnoreCase("exclude") ? List.of("true", "false") : List.of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}

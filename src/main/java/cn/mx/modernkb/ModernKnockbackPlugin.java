package cn.mx.modernkb;

import cn.mx.modernkb.api.*;
import cn.mx.modernkb.combat.*;
import cn.mx.modernkb.compat.SyncCompatibility;
import cn.mx.modernkb.compat.RodVelocity;
import cn.mx.modernkb.compat.RodCollision;
import cn.mx.modernkb.compat.ClientCollisions;
import cn.mx.modernkb.compat.LeafVelocityDispatch;
import cn.mx.modernkb.config.*;
import cn.mx.modernkb.gui.KnockbackGui;
import cn.mx.modernkb.rod.RodListener;
import cn.mx.modernkb.profiler.KnockbackProfiler;
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
    private KnockbackGui gui;
    private KnockbackProfiler profiler;
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
            CombatWarmup.prepare(settings.profiles().get("default"));
            tracker = new CombatTracker(this, () -> settings);
            sync = new SyncCompatibility(); sync.refresh();
            LeafVelocityDispatch.initialize();
            classic = new ClassicCombat(this, () -> settings, players, tracker, RodVelocity.forServer());
            pushing = new PlayerPushRules(this, () -> settings, players);
            collisions = new ClientCollisions(this, () -> settings, players);
            gui = new KnockbackGui(this, () -> settings, this::editGuiProfile);
            profiler = new KnockbackProfiler(this, () -> settings, players, tracker, this::profilerCounters, sync::status);
            knockback = new KnockbackListener(() -> settings, players, tracker, sync::active, classic, profiler);
            rods = new RodListener(this, () -> settings, players, tracker, classic, RodCollision.forServer());
            for (var listener : List.of(tracker, sync, pushing, collisions, classic, knockback, rods, gui, profiler)) Bukkit.getPluginManager().registerEvents(listener, this);
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
        if (profiler != null) profiler.close();
        Bukkit.getServicesManager().unregisterAll(this);
        HandlerList.unregisterAll(this);
        Bukkit.getAsyncScheduler().cancelTasks(this);
        if (rods != null) rods.close();
        gui = null;
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
        if (profiler != null) profiler.configurationChanged(settings);
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
        if (!sender.hasPermission("modernkb.admin")) {
            Messages.err(sender, "缺少权限节点 §7modernkb.admin");
            return true;
        }
        try {
            if (command.getName().equalsIgnoreCase("kbtest")) {
                if (args.length == 2 && args[0].equalsIgnoreCase("profiler")) {
                    profiler.command(sender, args[1]);
                } else if (args.length == 1 && (args[0].equalsIgnoreCase("classic") || args[0].equalsIgnoreCase("modern"))) {
                    CombatMode mode = CombatMode.parse(args[0]);
                    update(sender, () -> setMode(mode));
                } else if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                    update(sender, this::reloadMode);
                } else Messages.tip(sender, "用法 §e/kbtest classic|modern|reload §8| §e/kbtest profiler open|stop|status|report §7当前模式 §b" + settings.mode().key());
                return true;
            }
            if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
                status(sender);
            } else if (args[0].equalsIgnoreCase("gui") && args.length == 1) {
                if (!(sender instanceof Player player)) { Messages.err(sender, "§e/kb gui §f只能在游戏内由玩家打开"); return true; }
                gui.open(player);
            } else if (args[0].equalsIgnoreCase("list") && args.length == 1) {
                Messages.plain(sender, Messages.row("可用 profile", "§f" + String.join("§8, §f", new TreeSet<>(profiles()))));
            } else if (args[0].equalsIgnoreCase("view") && args.length == 2) {
                var profile = settings.profiles().get(args[1]);
                if (profile == null) throw new IllegalArgumentException("未知 profile: " + args[1]);
                Messages.plain(sender, Messages.row("§b" + args[1], "§f" + profile));
            } else if (args[0].equalsIgnoreCase("reload") && args.length == 1) {
                update(sender, this::reloadMode);
            } else if (args[0].equalsIgnoreCase("get") && args.length == 2) {
                UUID id = resolve(args[1]);
                Messages.plain(sender, Messages.row("玩家", "§f" + id));
                Messages.plain(sender, Messages.row("profile", "§f" + getProfile(id) + " §8| §7已排除 §8» §f" + isExcluded(id)));
            } else if (args[0].equalsIgnoreCase("setprofile") && args.length == 3) {
                UUID id = resolve(args[1]); String profile = args[2];
                update(sender, () -> setProfile(id, profile));
            } else if (args[0].equalsIgnoreCase("exclude") && args.length == 3) {
                UUID id = resolve(args[1]);
                if (!args[2].equalsIgnoreCase("true") && !args[2].equalsIgnoreCase("false")) throw new IllegalArgumentException("第三个参数只能是 §etrue §f或 §efalse");
                boolean value = Boolean.parseBoolean(args[2]);
                update(sender, () -> setExcluded(id, value));
            } else help(sender);
        } catch (IllegalArgumentException ex) { Messages.err(sender, ex.getMessage()); }
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
    private void editGuiProfile(Player player, KnockbackGui.Edit edit) {
        if (!player.hasPermission("modernkb.admin")) {
            player.closeInventory();
            Messages.err(player, "缺少权限节点 §7modernkb.admin");
            return;
        }
        if (!updating.compareAndSet(false, true)) {
            Messages.tip(player, "另一个配置操作正在进行，请稍候");
            return;
        }
        try {
            Bukkit.getAsyncScheduler().runNow(this, task -> {
                String result;
                try {
                    synchronized (configurationLock) {
                        if (settings.mode() != edit.mode()) throw new IllegalStateException("Mode changed; reopen /kb gui");
                        Settings candidate = modes.editProfile(edit.mode(), edit.profile(), edit.path(), edit.value());
                        validateAssignments(candidate);
                        settings = candidate;
                        refreshCombat();
                    }
                    result = Messages.PREFIX + "§a[+] §f已保存 §7" + edit.profile() + "." + edit.path() + " §8» §f" + edit.value();
                } catch (RuntimeException | IOException ex) {
                    result = Messages.PREFIX + "§c[!] §f保存失败，保留原设置 §8» §7" + ex.getMessage();
                    getLogger().log(java.util.logging.Level.WARNING, org.bukkit.ChatColor.stripColor(result), ex);
                }
                String message = result;
                try {
                    var scheduled = player.getScheduler().run(this, callback -> {
                        try {
                            if (!player.isOnline()) return;
                            if (gui.isOpen(player)) gui.open(player, edit);
                            Messages.plain(player, message);
                        } finally { updating.set(false); }
                    }, null);
                    if (scheduled == null) updating.set(false);
                } catch (RuntimeException ex) {
                    updating.set(false);
                    getLogger().log(java.util.logging.Level.WARNING, "Could not return to the KB menu", ex);
                }
            });
        } catch (RuntimeException ex) {
            updating.set(false);
            Messages.err(player, "配置操作排队失败 §8» §7" + ex.getMessage());
        }
    }
    private UUID resolve(String input) {
        try { return UUID.fromString(input); }
        catch (IllegalArgumentException ignored) {
            Player player = Bukkit.getPlayerExact(input);
            if (player == null) throw new IllegalArgumentException("玩家必须在线；离线玩家请填写 UUID");
            return player.getUniqueId();
        }
    }
    private void update(CommandSender sender, Runnable action) {
        if (!updating.compareAndSet(false, true)) { Messages.tip(sender, "另一个配置操作正在进行，请稍候"); return; }
        try {
            Bukkit.getAsyncScheduler().runNow(this, task -> {
                String result;
                try { action.run(); result = Messages.PREFIX + "§a[+] §f已保存并生效 §8| §7当前模式 §b" + settings.mode().key(); }
                catch (RuntimeException ex) {
                    result = Messages.PREFIX + "§c[!] §f操作失败，保留原状态 §8» §7" + ex.getMessage();
                    getLogger().log(java.util.logging.Level.WARNING, org.bukkit.ChatColor.stripColor(result), ex);
                } finally { updating.set(false); }
                String message = result;
                if (sender instanceof Entity entity) entity.getScheduler().run(this, t -> Messages.plain(sender, message), null);
                else Bukkit.getGlobalRegionScheduler().run(this, t -> Messages.plain(sender, message));
            });
        } catch (RuntimeException ex) { updating.set(false); throw ex; }
    }
    private void status(CommandSender sender) {
        Messages.plain(sender, Messages.title("ModernKnockBack v" + getPluginMeta().getVersion()));
        Messages.plain(sender, Messages.row("服务器", "§f" + Bukkit.getName() + " " + Bukkit.getMinecraftVersion()
                + " §8| §7regionized §8» §f" + SyncCompatibility.foliaRuntime()));
        Messages.plain(sender, Messages.row("插件状态", Messages.onOff(settings.enabled())));
        Messages.plain(sender, Messages.row("战斗模式", "§b" + settings.mode().key()
                + " §8(§7" + getDataPath().resolve(settings.mode().key()) + "§8)"));
        Messages.plain(sender, Messages.row("Profiles", "§f" + profiles().size() + " 个"));
        Messages.plain(sender, Messages.row("KB 处理", "§f" + knockback.handled() + " §7次"));
        Messages.plain(sender, Messages.row("原生公式", "§a命中 §f" + knockback.nativeHandled()
                + " §8| §e回退 §f" + knockback.nativeFallbacks() + " §8| §c不匹配 §f" + knockback.nativeMismatches()));
        Messages.plain(sender, Messages.row("同步插件", "§f" + sync.status() + " §8| §7策略 §8» §f"
                + (settings.preserveSyncY() ? "preserve-y" : "before-sync")));
        Messages.plain(sender, Messages.row("Classic 统计", "§f向量 §f" + classic.applied()
                + " §8| §7提前命中拦截 §f" + classic.preventedHits() + " §8| §7去重分量 §f" + classic.preventedComponents()));
        if (settings.classicEnabled()) {
            Messages.plain(sender, Messages.RULE);
            Messages.plain(sender, "  §b§lClassic 参数");
            Messages.plain(sender, Messages.bullet("攻击蓄力", "§f无 §8(§7modern-items 除外§8)"));
            Messages.plain(sender, Messages.bullet("受击间隔", "§f" + settings.classic().hitIntervalTicks()
                    + " §7tick §8| §7鱼竿 §f" + settings.classic().effectiveRodHitTicks()
                    + " §8| §7鱼竿接刀 §f" + settings.classic().effectiveRodMeleeTicks()));
            Messages.plain(sender, Messages.bullet("速度同步保护", Messages.onOff(settings.classic().protectVelocityFromSync())
                    + " §8| §7stop_sprint §8» " + Messages.onOff(settings.classic().stopSprint())));
            Messages.plain(sender, Messages.bullet("jump reset", Messages.onOff(settings.classic().jumpResetEnabled())
                    + " §8(§7窗口 §f" + settings.classic().jumpResetWindowTicks() + " §7tick§8)"));
            Messages.plain(sender, Messages.bullet("点击密度", Messages.onOff(settings.classic().clickDensityEnabled())
                    + " §8(§7上限 §f20%§8)"));
            Messages.plain(sender, Messages.bullet("旧版命中特效", Messages.onOff(settings.classic().legacyHitEffects())));
            Messages.plain(sender, Messages.bullet("旧版斧头伤害", Messages.onOff(settings.classic().legacyAxeDamage())));
            Messages.plain(sender, Messages.bullet("原生命中检测", "§f原版攻击距离 §8| §7鱼钩余量 §f0.3 §8| §7玩家互推 §8关闭"));
            Messages.plain(sender, Messages.bullet("Modern 工具例外", "§f" + settings.items().modernItems().size()
                    + " 个 §8(§7classic/item.yml§8)"));
            Messages.plain(sender, Messages.bullet("例外 KB 来源", "§f" + (settings.items().modern() == null ? "未启用"
                    : "modern/ §8» §7" + (settings.items().modern().preserveSyncY() ? "preserve-y" : "before-sync"))));
        }
        Messages.plain(sender, Messages.RULE);
    }

    private Map<String, Object> profilerCounters() {
        Map<String, Object> values = new LinkedHashMap<>();
        if (knockback != null) {
            values.put("processedImpulses", knockback.handled());
            values.put("nativeCalculations", knockback.nativeHandled());
            values.put("nativeFallbacks", knockback.nativeFallbacks());
            values.put("nativeMismatches", knockback.nativeMismatches());
        }
        if (classic != null) {
            values.put("classicVectors", classic.applied());
            values.put("blockedEarlyHits", classic.preventedHits());
            values.put("deduplicatedComponents", classic.preventedComponents());
        }
        return values;
    }

    private void help(CommandSender sender) {
        Messages.plain(sender, Messages.title("命令帮助"));
        Messages.plain(sender, Messages.usage("/mkb help", "查看命令帮助（别名 /kb、/modernkb）"));
        Messages.plain(sender, Messages.usage("/kb gui", "打开图形化配置菜单（仅玩家可用）"));
        Messages.plain(sender, Messages.usage("/mkb status", "查看插件、服务器与击退统计"));
        Messages.plain(sender, Messages.usage("/mkb list", "列出所有 profile"));
        Messages.plain(sender, Messages.usage("/mkb view <profile>", "查看某个 profile 的完整参数"));
        Messages.plain(sender, Messages.usage("/mkb reload", "校验并重载当前模式配置"));
        Messages.plain(sender, Messages.usage("/mkb get <玩家|UUID>", "查看玩家的 profile 与排除状态"));
        Messages.plain(sender, Messages.usage("/mkb setprofile <玩家|UUID> <profile>", "为玩家指定 profile"));
        Messages.plain(sender, Messages.usage("/mkb exclude <玩家|UUID> <true|false>", "设置是否跳过该玩家"));
        Messages.plain(sender, Messages.usage("/kbtest classic|modern", "切换战斗模式"));
        Messages.plain(sender, Messages.usage("/kbtest reload", "重载当前模式配置"));
        Messages.plain(sender, Messages.usage("/kbtest profiler open|stop", "开始 KB 检测／停止并生成离线 HTML 报告"));
        Messages.plain(sender, Messages.usage("/kbtest profiler status|report", "查看采集状态／最近一次报告文件链接"));
        Messages.plain(sender, Messages.RULE);
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("modernkb.admin")) return List.of();
        if (command.getName().equalsIgnoreCase("kbtest")) {
            if (args.length == 2 && args[0].equalsIgnoreCase("profiler")) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return List.of("open", "stop", "status", "report").stream().filter(s -> s.startsWith(prefix)).toList();
            }
            String prefix = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
            return args.length <= 1 ? List.of("classic", "modern", "reload", "profiler").stream().filter(s -> s.startsWith(prefix)).toList() : List.of();
        }
        Collection<String> choices = args.length == 1 ? List.of("help", "gui", "status", "reload", "list", "view", "get", "setprofile", "exclude")
                : args.length == 2 && args[0].equalsIgnoreCase("view") || args.length == 3 && args[0].equalsIgnoreCase("setprofile") ? profiles()
                : args.length == 3 && args[0].equalsIgnoreCase("exclude") ? List.of("true", "false") : List.of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}

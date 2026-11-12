package cn.mx.modernkb.gui;

import cn.mx.modernkb.Messages;
import cn.mx.modernkb.ModernKnockbackPlugin;
import cn.mx.modernkb.api.CombatMode;
import cn.mx.modernkb.config.KnockbackProfile;
import cn.mx.modernkb.config.Settings;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Per-mode profile editor. Every click is saved and validated before its runtime snapshot is replaced. */
public final class KnockbackGui implements Listener {
    public record Edit(CombatMode mode, String profile, GuiParameterCatalog.Section section, int page,
                       String path, Object value) {}
    @FunctionalInterface public interface Editor { void update(Player player, Edit edit); }

    private enum Screen { PROFILES, SECTIONS, PARAMETERS }
    private static final int PAGE_SIZE = 12;
    private static final String SEP = "§8" + "-".repeat(20);
    private static final Material FILLER = Material.GRAY_STAINED_GLASS_PANE;
    private final ModernKnockbackPlugin plugin;
    private final Supplier<Settings> settings;
    private final Editor editor;

    public KnockbackGui(ModernKnockbackPlugin plugin, Supplier<Settings> settings, Editor editor) {
        this.plugin = Objects.requireNonNull(plugin);
        this.settings = Objects.requireNonNull(settings);
        this.editor = Objects.requireNonNull(editor);
    }

    public void open(Player player) {
        if (!authorized(player)) return;
        Settings current = settings.get();
        openProfiles(player, current.mode(), current);
    }

    public boolean isOpen(Player player) {
        return player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder;
    }

    public void open(Player player, Edit edit) {
        if (!authorized(player)) return;
        Settings current = settings.get();
        if (edit.mode() != current.mode() || !current.profiles().containsKey(edit.profile())) {
            if (edit.mode() == current.mode() && edit.profile().equals("defaults")) {
                openParameters(player, current, edit.profile(), edit.section(), edit.page());
                return;
            }
            openProfiles(player, current.mode(), current);
            return;
        }
        openParameters(player, current, edit.profile(), edit.section(), edit.page());
    }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (!authorized(player)) return;
        if (event.getClickedInventory() != top || event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) return;
        if (event.getClick() != ClickType.LEFT) return;
        Settings current = settings.get();
        if (current.mode() != holder.mode) {
            openProfiles(player, current.mode(), current);
            Messages.tip(player, "模式已切换，已打开当前模式的配置");
            return;
        }
        if (holder.profile != null && !holder.profile.equals("defaults")
                && !current.profiles().containsKey(holder.profile)) {
            openProfiles(player, current.mode(), current);
            Messages.tip(player, "该 profile 已被移除，已返回配置列表");
            return;
        }
        switch (holder.screen) {
            case PROFILES -> clickProfile(player, holder, current, event.getRawSlot());
            case SECTIONS -> clickSection(player, holder, current, event.getRawSlot());
            case PARAMETERS -> clickParameter(player, holder, current, event.getRawSlot());
        }
    }

    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) event.setCancelled(true);
    }

    private boolean authorized(Player player) {
        if (player.hasPermission("modernkb.admin")) return true;
        player.closeInventory();
        Messages.err(player, "缺少权限节点 §7modernkb.admin");
        return false;
    }

    private void clickProfile(Player player, MenuHolder holder, Settings current, int slot) {
        if (slot >= 45 && slot < 54) {
            if (slot == 45 || slot == 53) player.closeInventory();
            else if (slot == 48) openProfiles(player, holder.mode, current, Math.max(0, holder.page - 1));
            else if (slot == 50) openProfiles(player, holder.mode, current, holder.page + 1);
            return;
        }
        List<String> profiles = sortedProfiles(current);
        int index = holder.page * 45 + slot;
        if (index >= profiles.size()) return;
        String profile = profiles.get(index);
        openSections(player, current, profile);
    }

    private void clickSection(Player player, MenuHolder holder, Settings current, int slot) {
        if (slot == 18) { openProfiles(player, holder.mode, current); return; }
        if (slot == 26) { player.closeInventory(); return; }
        if (slot == 11 || slot == 13 || slot == 15) {
            var section = GuiParameterCatalog.sections().get((slot - 11) / 2);
            openParameters(player, current, holder.profile, section, 0);
        }
    }

    private void clickParameter(Player player, MenuHolder holder, Settings current, int slot) {
        if (slot >= 45 && slot < 54) {
            if (slot == 45) openProfiles(player, holder.mode, current);
            else if (slot == 48) openSections(player, current, holder.profile);
            else if (slot == 50) openParameters(player, current, holder.profile, holder.section, Math.max(0, holder.page - 1));
            else if (slot == 52) openParameters(player, current, holder.profile, holder.section, holder.page + 1);
            else if (slot == 53) player.closeInventory();
            return;
        }
        int row = slot / 9;
        int column = slot % 9;
        if (row > 3) return;
        int index = row * 3 + column / 3 + holder.page * PAGE_SIZE;
        List<GuiParameterCatalog.Parameter> parameters = GuiParameterCatalog.parameters(holder.mode, holder.section);
        if (index >= parameters.size()) return;
        var parameter = parameters.get(index);
        if (parameter.kind() == GuiParameterCatalog.Kind.BOOLEAN || parameter.kind() == GuiParameterCatalog.Kind.CHOICE) {
            if (column % 3 != 1) return;
        } else if (column % 3 == 1) return;
        int direction = column % 3 == 0 ? -1 : 1;
        if (parameter.kind() != GuiParameterCatalog.Kind.NUMBER) direction = 1;
        Object previous = parameter.value(selectedProfile(current, holder.profile));
        Object next = parameter.change(previous, direction);
        editor.update(player, new Edit(holder.mode, holder.profile, holder.section, holder.page, parameter.path(), next));
    }

    private void openProfiles(Player player, CombatMode mode, Settings current) { openProfiles(player, mode, current, 0); }
    private void openProfiles(Player player, CombatMode mode, Settings current, int page) {
        List<String> profiles = sortedProfiles(current);
        int pageCount = Math.max(1, (profiles.size() + 44) / 45);
        int selectedPage = Math.clamp(page, 0, pageCount - 1);
        MenuHolder holder = new MenuHolder(Screen.PROFILES, mode, null, null, selectedPage,
                "§8KB §f" + mode.key() + " §8| Profiles");
        int start = selectedPage * 45;
        for (int slot = 0; slot < 45 && start + slot < profiles.size(); slot++) {
            String name = profiles.get(start + slot);
            KnockbackProfile profile = selectedProfile(current, name);
            boolean enabled = profile.enabled();
            Material icon = enabled ? Material.ENCHANTED_BOOK : Material.BARRIER;
            String displayName = name.equals("defaults") ? "全局默认值" : name;
            holder.inventory.setItem(slot, item(icon, (enabled ? "§a" : "§c") + displayName, List.of(
                    SEP,
                    "§7模式   §8» §f" + mode.key(),
                    "§7水平KB §8» §f" + format(profile, mode),
                    "§7状态   §8» " + (enabled ? "§a启用" : "§c停用"),
                    "§8",
                    "§e[>] §7点击进入参数编辑")));
        }
        fill(holder.inventory, 0, 45);
        footer(holder.inventory, 45, Material.BARRIER, "关闭菜单", true);
        footer(holder.inventory, 48, Material.ARROW, "上一页", selectedPage > 0);
        holder.inventory.setItem(49, item(Material.BOOK, "§e" + mode.key() + " §8» §fProfile 列表", List.of(
                SEP,
                "§7配置文件 §8» §f" + mode.key() + "/profiles.yml",
                "§7Profile 数 §8» §f" + profiles.size(),
                "§7页码     §8» §f" + (selectedPage + 1) + "§8/§f" + pageCount)));
        footer(holder.inventory, 50, Material.ARROW, "下一页", selectedPage + 1 < pageCount);
        player.openInventory(holder.inventory);
    }

    private void openSections(Player player, Settings current, String profile) {
        CombatMode mode = current.mode();
        MenuHolder holder = new MenuHolder(Screen.SECTIONS, mode, profile, null, 0,
                "§8KB §f" + mode.key() + " §8| §f" + profile);
        fill(holder.inventory, 0, 27);
        Material[] icons = {Material.IRON_SWORD, Material.REPEATER, Material.FISHING_ROD};
        String[] accents = {"§c", "§e", "§b"};
        int[] slots = {11, 13, 15};
        for (int i = 0; i < slots.length; i++) {
            var section = GuiParameterCatalog.sections().get(i);
            holder.inventory.setItem(slots[i], item(icons[i], accents[i] + section.title(), List.of(
                    SEP,
                    "§7" + section.description(),
                    "§8",
                    "§7profile §8» §f" + profile,
                    "§e[>] §7点击展开参数")));
        }
        holder.inventory.setItem(4, item(Material.BOOK, "§e" + mode.key() + " §8» §f" + profile,
                List.of(SEP, "§7参数只写入当前模式的 profiles.yml。", "§7修改后立即校验并生效。")));
        footer(holder.inventory, 18, Material.ARROW, "返回 profile 列表", true);
        footer(holder.inventory, 26, Material.BARRIER, "关闭菜单", true);
        player.openInventory(holder.inventory);
    }

    private void openParameters(Player player, Settings current, String profile,
                                GuiParameterCatalog.Section section, int page) {
        CombatMode mode = current.mode();
        List<GuiParameterCatalog.Parameter> parameters = GuiParameterCatalog.parameters(mode, section);
        int pageCount = Math.max(1, (parameters.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int selectedPage = Math.clamp(page, 0, pageCount - 1);
        MenuHolder holder = new MenuHolder(Screen.PARAMETERS, mode, profile, section, selectedPage,
                "§8KB §f" + mode.key() + " §8| §f" + section.title());
        KnockbackProfile values = selectedProfile(current, profile);
        int start = selectedPage * PAGE_SIZE;
        for (int offset = 0; offset < PAGE_SIZE && start + offset < parameters.size(); offset++) {
            var parameter = parameters.get(start + offset);
            int group = offset % 3;
            int row = offset / 3;
            int base = row * 9 + group * 3;
            Object value = parameter.value(values);
            if (parameter.kind() == GuiParameterCatalog.Kind.NUMBER) {
                String amount = GuiParameterCatalog.Parameter.format(parameter.step());
                holder.inventory.setItem(base, item(Material.REDSTONE, "§c- " + amount,
                        List.of(SEP, "§7参数 §8» §f" + parameter.label(),
                                "§7调整幅度 §8» §f" + amount, "§8", "§c[左键] §7数值减小")));
                holder.inventory.setItem(base + 2, item(Material.EMERALD, "§a+ " + amount,
                        List.of(SEP, "§7参数 §8» §f" + parameter.label(),
                                "§7调整幅度 §8» §f" + amount, "§8", "§a[左键] §7数值增大")));
            } else {
                holder.inventory.setItem(base, item(FILLER, " ", List.of()));
                holder.inventory.setItem(base + 2, item(FILLER, " ", List.of()));
            }
            holder.inventory.setItem(base + 1, parameter(parameter, value));
        }
        fill(holder.inventory, 36, 45);
        footer(holder.inventory, 45, Material.BOOK, "profile 列表", true);
        footer(holder.inventory, 48, Material.ARROW, "返回参数分类", true);
        footer(holder.inventory, 50, Material.ARROW, "上一页", selectedPage > 0);
        footer(holder.inventory, 52, Material.ARROW, "下一页", selectedPage + 1 < pageCount);
        footer(holder.inventory, 53, Material.BARRIER, "关闭菜单", true);
        player.openInventory(holder.inventory);
    }

    private static ItemStack parameter(GuiParameterCatalog.Parameter parameter, Object value) {
        Material icon = switch (parameter.kind()) {
            case NUMBER -> Material.WRITABLE_BOOK;
            case BOOLEAN -> Boolean.TRUE.equals(value) ? Material.LIME_DYE : Material.GRAY_DYE;
            case CHOICE -> Material.COMPARATOR;
        };
        var lore = new ArrayList<String>();
        lore.add(SEP);
        lore.add("§7当前值 §8» §a" + GuiParameterCatalog.Parameter.format(value));
        lore.add("§8");
        lore.add("§7" + parameter.explanation());
        lore.add("§8");
        if (parameter.kind() == GuiParameterCatalog.Kind.NUMBER) {
            lore.add("§7范围位置 §8» " + gauge(((Number) value).doubleValue(), parameter.min(), parameter.max()));
            lore.add("§7范围 §f" + GuiParameterCatalog.Parameter.format(parameter.min())
                    + " §7.. §f" + GuiParameterCatalog.Parameter.format(parameter.max())
                    + " §8| §7步长 §f" + GuiParameterCatalog.Parameter.format(parameter.step()));
        } else {
            lore.add("§7类型 §8» §f" + (parameter.kind() == GuiParameterCatalog.Kind.BOOLEAN ? "开关" : "二选一"));
            lore.add("§e[>] §7点击切换当前值");
        }
        lore.add("§8配置项: " + parameter.path());
        return item(icon, "§b" + parameter.label() + " §8» §f" + GuiParameterCatalog.Parameter.format(value), lore);
    }

    /** Renders a 12-cell bar showing where the value sits inside its allowed range. */
    private static String gauge(double value, double min, double max) {
        int total = 12;
        double ratio = max > min ? (value - min) / (max - min) : 0;
        ratio = Math.clamp(ratio, 0, 1);
        int filled = (int) Math.round(ratio * total);
        return "§a" + "|".repeat(filled) + "§8" + "|".repeat(total - filled)
                + " §7" + Math.round(ratio * 100) + "%";
    }

    /** Fills empty slots in {@code [from, to)} with the menu background pane. */
    private static void fill(Inventory inventory, int from, int to) {
        ItemStack pane = item(FILLER, " ", List.of());
        for (int slot = from; slot < to; slot++) {
            if (inventory.getItem(slot) == null) inventory.setItem(slot, pane);
        }
    }

    static List<String> sortedProfiles(Settings current) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of("defaults"), current.profiles().keySet().stream()
                        .filter(name -> !name.equals("default")))
                .sorted(Comparator
                .comparing((String name) -> !name.equals("default"))
                .thenComparing(name -> name.equals("defaults") ? "" : name.toLowerCase(Locale.ROOT))).toList();
    }

    private static KnockbackProfile selectedProfile(Settings current, String name) {
        return current.profiles().get(name.equals("defaults") ? "default" : name);
    }

    private static String format(KnockbackProfile profile, CombatMode mode) {
        double value = mode == CombatMode.CLASSIC ? profile.classic().horizontalGround() : profile.groundHorizontal();
        return GuiParameterCatalog.Parameter.format(value);
    }

    private static void footer(Inventory inventory, int slot, Material icon, String label, boolean enabled) {
        inventory.setItem(slot, enabled
                ? item(icon, "§e" + label, List.of("§8[左键] §7执行"))
                : item(FILLER, "§8" + label, List.of("§8当前不可用")));
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private static final class MenuHolder implements InventoryHolder {
        private final Screen screen;
        private final CombatMode mode;
        private final String profile;
        private final GuiParameterCatalog.Section section;
        private final int page;
        private final Inventory inventory;
        private MenuHolder(Screen screen, CombatMode mode, String profile, GuiParameterCatalog.Section section,
                           int page, String title) {
            this.screen = screen;
            this.mode = mode;
            this.profile = profile;
            this.section = section;
            this.page = page;
            this.inventory = Bukkit.createInventory(this, screen == Screen.SECTIONS ? 27 : 54, title);
        }
        @Override public Inventory getInventory() { return inventory; }
    }
}

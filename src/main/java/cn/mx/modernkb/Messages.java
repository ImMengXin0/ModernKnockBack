package cn.mx.modernkb;

import org.bukkit.command.CommandSender;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

/**
 * Shared chat formatting for every command response.
 *
 * <p>Palette: {@code §b} accent, {@code §7} muted, {@code §f} value, {@code §a} success,
 * {@code §e} warning, {@code §c} error. Tags are plain ASCII on purpose so they render with the
 * default Minecraft font.
 */
public final class Messages {
    public static final String PREFIX = "§8[§bKB§8]§r ";
    public static final String RULE = "§8" + "-".repeat(42);

    private Messages() {}

    public static void ok(CommandSender target, String text) {
        plain(target, PREFIX + "§a[+] §f" + text);
    }

    public static void err(CommandSender target, String text) {
        plain(target, PREFIX + "§c[!] §f" + text);
    }

    public static void tip(CommandSender target, String text) {
        plain(target, PREFIX + "§e[>] §f" + text);
    }

    public static void note(CommandSender target, String text) {
        plain(target, PREFIX + "§8[-] §7" + text);
    }

    public static void plain(CommandSender target, String line) {
        target.sendMessage(target instanceof Player ? line : ChatColor.stripColor(line));
    }

    public static String title(String text) {
        return "§8" + "-".repeat(4) + "§r §b§l" + text + "§r §8" + "-".repeat(4);
    }

    /** {@code key » value} row used by status output. */
    public static String row(String key, String value) {
        return "  §7" + key + " §8» " + value;
    }

    /** {@code key » value} row with a coloured bullet. */
    public static String bullet(String key, String value) {
        return "  §b* §7" + key + " §8» " + value;
    }

    public static String usage(String command, String description) {
        return "  §e" + command + " §8- §7" + description;
    }

    public static String yes(boolean value) {
        return value ? "§a是" : "§c否";
    }

    public static String onOff(boolean value) {
        return value ? "§a开启" : "§8关闭";
    }

    public static String num(long value) {
        return "§f" + value;
    }
}

package cn.mx.modernkb.combat;

import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;

/** Read on the owning entity region; retain only a material name in combat snapshots. */
public final class CombatItems {
    private CombatItems() {}
    public static String held(LivingEntity entity) {
        if (!CombatTracker.owns(entity)) return "";
        ItemStack item = entity instanceof Player player
                ? player.getInventory() == null ? null : player.getInventory().getItemInMainHand()
                : entity.getEquipment() == null ? null : entity.getEquipment().getItemInMainHand();
        return item == null ? "AIR" : item.getType().name();
    }
}

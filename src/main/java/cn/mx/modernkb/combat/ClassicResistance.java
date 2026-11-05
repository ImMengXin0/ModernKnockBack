package cn.mx.modernkb.combat;

import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;

/** Reads resistance for a Classic hit without changing the entity's actual attributes. */
final class ClassicResistance {
    private ClassicResistance() {}

    static double value(LivingEntity victim) {
        var attribute = victim.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (attribute == null) return 0;
        var equipment = victim.getEquipment();
        if (equipment == null) return attribute.getValue();
        boolean ignoredArmor = false;
        double added = 0, scalar = 0, multiplier = 1;
        for (var modifier : attribute.getModifiers()) {
            if (vanillaNetherite(modifier, equipment)) {
                ignoredArmor = true;
                continue;
            }
            switch (modifier.getOperation()) {
                case ADD_NUMBER -> added += modifier.getAmount();
                case ADD_SCALAR -> scalar += modifier.getAmount();
                case MULTIPLY_SCALAR_1 -> multiplier *= 1 + modifier.getAmount();
            }
        }
        if (!ignoredArmor) return attribute.getValue();
        // Recompute before the native 0..1 clamp: subtracting armor from getValue()
        // loses resistance from other sources when the original value was capped.
        return Math.clamp((attribute.getBaseValue() + added) * (1 + scalar) * multiplier, 0, 1);
    }

    private static boolean vanillaNetherite(AttributeModifier modifier, EntityEquipment equipment) {
        if (!modifier.getKey().getNamespace().equals("minecraft")
                || modifier.getOperation() != AttributeModifier.Operation.ADD_NUMBER
                || Math.abs(modifier.getAmount() - (double) 0.1f) > 1e-8) return false;
        EquipmentSlot slot;
        Material material;
        switch (modifier.getKey().getKey()) {
            case "armor.helmet" -> { slot = EquipmentSlot.HEAD; material = Material.NETHERITE_HELMET; }
            case "armor.chestplate" -> { slot = EquipmentSlot.CHEST; material = Material.NETHERITE_CHESTPLATE; }
            case "armor.leggings" -> { slot = EquipmentSlot.LEGS; material = Material.NETHERITE_LEGGINGS; }
            case "armor.boots" -> { slot = EquipmentSlot.FEET; material = Material.NETHERITE_BOOTS; }
            default -> { return false; }
        }
        var item = equipment.getItem(slot);
        return item != null && item.getType() == material;
    }
}

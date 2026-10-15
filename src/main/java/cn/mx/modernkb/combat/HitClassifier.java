package cn.mx.modernkb.combat;

import cn.mx.modernkb.api.HitType;
import org.bukkit.entity.*;

public final class HitClassifier {
    private HitClassifier() {}
    /** Caller must own the source's region. */
    public static HitType source(Entity source) {
        if (source instanceof FishHook) return HitType.FISHING_ROD;
        if (source instanceof Trident) return HitType.TRIDENT;
        if (source instanceof AbstractArrow arrow) return arrow.isShotFromCrossbow() ? HitType.CROSSBOW : HitType.ARROW;
        String type = source.getType().name();
        if (type.contains("WIND_CHARGE")) return HitType.WIND_CHARGE;
        if (type.equals("END_CRYSTAL")) return HitType.CRYSTAL;
        if (type.equals("TNT") || type.equals("TNT_MINECART")) return HitType.TNT;
        if (source instanceof Projectile) return HitType.OTHER_PROJECTILE;
        if (source instanceof LivingEntity living) {
            var equipment = living.getEquipment();
            if (equipment != null) return weapon(equipment.getItemInMainHand().getType().name());
            return HitType.MELEE;
        }
        return HitType.UNKNOWN;
    }
    public static HitType weapon(String material) {
        if (material.equals("MACE")) return HitType.MACE;
        if (material.endsWith("_SPEAR")) return HitType.SPEAR;
        return HitType.MELEE;
    }
    public static HitType cause(String cause, HitType source) {
        return switch (cause) {
            case "SWEEP_ATTACK" -> HitType.SWEEP;
            case "SHIELD_BLOCK" -> HitType.SHIELD_BLOCK;
            case "EXPLOSION" -> switch (source) {
                case CRYSTAL, TNT, WIND_CHARGE -> source;
                default -> HitType.EXPLOSION;
            };
            case "DAMAGE" -> source == HitType.UNKNOWN ? HitType.DAMAGE : source;
            default -> source;
        };
    }
}

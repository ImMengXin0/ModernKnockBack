package cn.mx.modernkb.api;

import java.util.UUID;
import java.util.Set;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

public interface ModernKnockbackAPI {
    default CombatMode getMode() { return CombatMode.MODERN; }
    /** Durable mode switch and validation. Run from the async scheduler, not a combat event. */
    default void setMode(CombatMode mode) { throw new UnsupportedOperationException("Mode switching requires ModernKnockback 1.2+"); }
    Set<String> profiles();
    String getProfile(UUID player);
    boolean isExcluded(UUID player);
    /** Durable before returning. Uses only UUIDs, may run on the async scheduler. */
    void setProfile(UUID player, String profile);
    void setExcluded(UUID player, boolean excluded);
    /** Entity operations require that entity's owning region. Stored in its PDC. */
    void setBot(Entity entity, Boolean bot);
    /** Scope the actual synchronous damage call; both entities must be owned by this region. */
    void withHitType(LivingEntity attacker, LivingEntity victim, HitType type, Runnable action);
}

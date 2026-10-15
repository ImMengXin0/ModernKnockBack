package cn.mx.modernkb.compat;

import java.lang.reflect.*;
import java.util.function.Predicate;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.LivingEntity;

/** Uses the core's block-clipped projectile sweep with the constant 1.8 fishing-hook margin. */
@FunctionalInterface
public interface RodCollision {
    float LEGACY_MARGIN = 0.3F;

    LivingEntity trace(FishHook hook);

    static RodCollision nativeOnly() { return hook -> null; }

    static RodCollision forServer() {
        try {
            Bridge bridge = new Bridge();
            return hook -> {
                Object hit = bridge.trace(bridge.handle(hook));
                if (hit == null) return null;
                Object target = bridge.bukkit(hit);
                return target instanceof LivingEntity living ? living : null;
            };
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Cannot initialize 1.21.11 classic rod collision bridge", ex);
        }
    }

    /** Package visible so the offline probe executes this exact path against all supported cores. */
    final class Bridge {
        private final Method handle, position, movement, level, canHit, sweep, hitEntity, bukkit;
        private final Class<?> entityHit;
        private final Object collider;

        Bridge() throws ReflectiveOperationException {
            Class<?> entity = Class.forName("net.minecraft.world.entity.Entity");
            Class<?> projectile = Class.forName("net.minecraft.world.entity.projectile.Projectile");
            Class<?> vec = Class.forName("net.minecraft.world.phys.Vec3");
            Class<?> world = Class.forName("net.minecraft.world.level.Level");
            Class<?> block = Class.forName("net.minecraft.world.level.ClipContext$Block");
            entityHit = Class.forName("net.minecraft.world.phys.EntityHitResult");
            handle = Class.forName("org.bukkit.craftbukkit.entity.CraftFishHook").getMethod("getHandle");
            position = entity.getMethod("position"); movement = entity.getMethod("getDeltaMovement");
            level = entity.getMethod("level"); bukkit = entity.getMethod("getBukkitEntity");
            canHit = projectile.getMethod("canHitEntityPublic", entity);
            hitEntity = entityHit.getMethod("getEntity");
            collider = block.getField("COLLIDER").get(null);
            sweep = Class.forName("net.minecraft.world.entity.projectile.ProjectileUtil").getDeclaredMethod(
                    "getHitResult", vec, entity, Predicate.class, vec, world, float.class, block);
            sweep.setAccessible(true);
        }

        Object handle(FishHook hook) { return invoke(handle, hook); }
        Object bukkit(Object entity) { return invoke(bukkit, entity); }

        Object trace(Object hook) {
            Predicate<Object> allowed = target -> (Boolean) invoke(canHit, hook, target);
            Object hit = invoke(sweep, null, invoke(position, hook), hook, allowed,
                    invoke(movement, hook), invoke(level, hook), LEGACY_MARGIN, collider);
            return entityHit.isInstance(hit) ? invoke(hitEntity, hit) : null;
        }

        private static Object invoke(Method method, Object receiver, Object... arguments) {
            try { return method.invoke(receiver, arguments); }
            catch (ReflectiveOperationException ex) {
                Throwable cause = ex instanceof InvocationTargetException invocation ? invocation.getCause() : ex;
                throw new IllegalStateException("Classic rod collision bridge failed", cause);
            }
        }
    }
}

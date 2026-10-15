package cn.mx.modernkb.compat;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import io.netty.channel.embedded.EmbeddedChannel;
import org.mockito.Mockito;

/** Offline native adapter check. Bootstraps registries, never starts a server or opens a port. */
public final class ServerBridgeProbe {
    public static void main(String[] args) throws Exception {
        Class.forName("net.minecraft.SharedConstants").getMethod("tryDetectVersion").invoke(null);
        Class.forName("net.minecraft.server.Bootstrap").getMethod("bootStrap").invoke(null);
        verifyProjectileCollision();
        verifyRodSweep();
        RodVelocity.forServer();
        TeamPacketCodec codec = new TeamPacketCodec();
        Class<?> boardClass = Class.forName("net.minecraft.world.scores.Scoreboard");
        Class<?> teamClass = Class.forName("net.minecraft.world.scores.PlayerTeam");
        Object team = teamClass.getConstructor(boardClass, String.class).newInstance(boardClass.getConstructor().newInstance(), "test");
        Class<?> component = Class.forName("net.minecraft.network.chat.Component");
        teamClass.getMethod("setPlayerPrefix", component).invoke(team, component.getMethod("literal", String.class).invoke(null, "[VIP]"));
        Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket");
        Object original = packetClass.getMethod("createAddOrModifyPacket", teamClass, boolean.class).invoke(null, team, true);
        var decoded = codec.decode(original);
        Object copy = codec.noCollision(decoded.parameters());
        Class<?> params = copy.getClass();
        for (String property : List.of("DisplayName", "PlayerPrefix", "PlayerSuffix", "Color", "NametagVisibility", "Options")) {
            Method getter = params.getMethod("get" + property);
            require(Objects.equals(getter.invoke(decoded.parameters()), getter.invoke(copy)), "Lost team property " + property);
        }
        Method rule = params.getMethod("getCollisionRule");
        require(rule.invoke(decoded.parameters()).toString().equals("ALWAYS"), "Original packet was mutated");
        require(rule.invoke(copy).toString().equals("NEVER"), "Client collision rule was not applied");
        var state = new CollisionTeams<Object>("Alice", "mkb_test", codec.noCollision(null), codec::noCollision);
        state.accept(decoded);
        for (var message : state.enabled(true)) require(codec.decode(codec.encode(message)).equals(message), "Team packet round trip failed");
        Class<?> bundle = Class.forName("net.minecraft.network.protocol.game.ClientboundBundlePacket");
        Object wrapped = bundle.getConstructor(Iterable.class).newInstance(List.of(original));
        var output = codec.transform(wrapped, state);
        Object child = ((Iterable<?>) bundle.getMethod("subPackets").invoke(output.getFirst())).iterator().next();
        require(rule.invoke(codec.decode(child).parameters()).toString().equals("NEVER"), "Bundled team was not rewritten");
        var restored = state.enabled(false).getLast();
        require(rule.invoke(codec.decode(codec.encode(restored)).parameters()).toString().equals("ALWAYS"), "Modern restore failed");
        // Exercise real Netty ordering: fallback, original packet rewrite, mode restore, remove.
        var channelState = new CollisionTeams<Object>("Alice", "mkb_test", codec.noCollision(null), codec::noCollision);
        var enabled = new AtomicBoolean(true);
        var channel = new EmbeddedChannel();
        var overlay = new ClientCollisions.Overlay(channel, channelState, codec, enabled::get,
                warning -> { throw new IllegalStateException(warning); });
        channel.pipeline().addLast("overlay", overlay);
        channel.writeOutbound(original);
        require(codec.decode(channel.readOutbound()).name().equals("mkb_test"), "Missing fallback for unteamed viewer");
        require(rule.invoke(codec.decode(channel.readOutbound()).parameters()).toString().equals("NEVER"), "Channel failed to rewrite team");
        enabled.set(false); overlay.refresh(); channel.runPendingTasks();
        require(codec.decode(channel.readOutbound()).method() == 1, "Fallback not removed on modern");
        require(rule.invoke(codec.decode(channel.readOutbound()).parameters()).toString().equals("ALWAYS"), "Channel failed to restore original");
        channel.writeOutbound(original);
        require(rule.invoke(codec.decode(channel.readOutbound()).parameters()).toString().equals("ALWAYS"), "Modern packet changed");
        enabled.set(true); overlay.refresh(); channel.runPendingTasks();
        while (channel.readOutbound() != null) { }
        overlay.remove(); channel.runPendingTasks();
        require(codec.decode(channel.readOutbound()).method() == 1, "Disable failed to remove fallback");
        require(rule.invoke(codec.decode(channel.readOutbound()).parameters()).toString().equals("ALWAYS"), "Disable failed to restore rules");
        require(channel.pipeline().context(overlay) == null, "Handler was not removed");
        channel.finishAndReleaseAll();
        System.out.println("Native bridge probe OK (no running server / no client PvP test)");
    }
    private static void verifyProjectileCollision() throws Exception {
        Class<?> entity = Class.forName("net.minecraft.world.entity.Entity");
        Class<?> living = Class.forName("net.minecraft.world.entity.LivingEntity");
        Class<?> nativePlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
        UUID otherId = UUID.randomUUID();
        // Mock only construction/world state. Execute the actual core targeting and collision methods.
        Object target = Mockito.mock(nativePlayer, call -> switch (call.getMethod().getName()) {
            case "isAlive", "isPushable" -> true;
            case "isRemoved", "isSpectator" -> false;
            case "getTeam" -> null;
            default -> call.callRealMethod();
        });
        Field collides = living.getField("collides");
        Set<UUID> exemptions = new HashSet<>();
        living.getField("collidableExemptions").set(target, exemptions);
        Object other = Mockito.mock(nativePlayer, call -> switch (call.getMethod().getName()) {
            case "getUUID" -> otherId;
            case "isSpectator" -> false;
            case "canCollideWithBukkit", "isPushable" -> true;
            default -> call.callRealMethod();
        });
        Class<?> hookClass = Class.forName("net.minecraft.world.entity.projectile.FishingHook");
        Object hook = Mockito.mock(hookClass, call -> call.getMethod().getName().equals("getOwner") ? null : call.callRealMethod());
        Method canHit = hookClass.getDeclaredMethod("canHitEntity", entity); canHit.setAccessible(true);
        Method push = living.getMethod("canCollideWithBukkit", entity);
        Method projectileTarget = entity.getMethod("canBeHitByProjectile");
        collides.setBoolean(target, false);
        require(!(Boolean) canHit.invoke(hook, target), "Failed to reproduce 1.2.8 rod regression");
        collides.setBoolean(target, true);
        require((Boolean) push.invoke(target, other), "Native push baseline failed");
        exemptions.add(otherId);
        require((Boolean) projectileTarget.invoke(target), "Exemptions disabled projectile targeting");
        require((Boolean) canHit.invoke(hook, target), "Rod cannot hit exempted player");
        require(!(Boolean) push.invoke(target, other), "Player push was not disabled");
        var selector = (java.util.function.Predicate<Object>) Class.forName("net.minecraft.world.entity.EntitySelector")
                .getMethod("pushableBy", entity).invoke(null, target);
        require(!selector.test(other), "Core push selector ignored exemptions");
        exemptions.remove(otherId);
        require((Boolean) push.invoke(target, other), "Modern restore did not recover native pushing");
        System.out.println("Native collision regression OK: 1.2.8 rod failure reproduced; projectile targeting and player push exemptions verified");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    private static void verifyRodSweep() throws Exception {
        var target = Mockito.mock(net.minecraft.server.level.ServerPlayer.class, call -> switch (call.getMethod().getName()) {
            case "isAlive" -> true;
            case "isRemoved", "isSpectator" -> false;
            default -> call.callRealMethod();
        });
        target.collides = true;
        var start = new net.minecraft.world.phys.Vec3(0, 65, 0);
        var motion = new net.minecraft.world.phys.Vec3(4, 0, 0);
        var wall = new AtomicBoolean();
        var world = Mockito.mock(net.minecraft.world.level.Level.class, call -> switch (call.getMethod().getName()) {
            case "getEntities" -> {
                var bounds = (net.minecraft.world.phys.AABB) call.getArgument(1);
                var predicate = (java.util.function.Predicate<net.minecraft.world.entity.Entity>) call.getArgument(2);
                yield bounds.intersects(target.getBoundingBox()) && predicate.test(target) ? List.of(target) : List.of();
            }
            case "clipIncludingBorder" -> {
                var clip = (net.minecraft.world.level.ClipContext) call.getArgument(0);
                yield wall.get() ? new net.minecraft.world.phys.BlockHitResult(new net.minecraft.world.phys.Vec3(1, 65, 0),
                        net.minecraft.core.Direction.WEST, new net.minecraft.core.BlockPos(1, 65, 0), false)
                        : net.minecraft.world.phys.BlockHitResult.miss(clip.getTo(), net.minecraft.core.Direction.WEST,
                                net.minecraft.core.BlockPos.containing(clip.getTo()));
            }
            default -> throw new IllegalStateException("Unexpected world operation: " + call.getMethod());
        });
        var hook = Mockito.mock(net.minecraft.world.entity.projectile.FishingHook.class, call -> switch (call.getMethod().getName()) {
            case "position" -> start;
            case "isDescending" -> false;
            case "getY" -> start.y;
            case "getDeltaMovement" -> motion;
            case "level" -> world;
            case "getBoundingBox" -> new net.minecraft.world.phys.AABB(-0.125, 65, -0.125, 0.125, 65.25, 0.125);
            case "getOwner" -> null;
            default -> call.callRealMethod();
        });
        var bridge = new RodCollision.Bridge();
        for (int tick = 0; tick <= 2; tick++) {
            hook.tickCount = tick;
            for (double z : new double[]{0.55, 0.45, 0.35}) {
                Mockito.doReturn(new net.minecraft.world.phys.AABB(1.7, 64, z - 0.3, 2.3, 65.8, z + 0.3)).when(target).getBoundingBox();
                var nativeHit = net.minecraft.world.entity.projectile.ProjectileUtil.getHitResultOnMoveVector(hook, hook::canHitEntityPublic);
                require(nativeHit.getType() == net.minecraft.world.phys.HitResult.Type.MISS, "Early native margin regression not reproduced");
                require(bridge.trace(hook) == target, "Classic margin missed moving-target snapshot at " + z);
                require(hook.tickCount == tick, "Classic trace changed hook age");
            }
        }
        hook.tickCount = 8;
        require(net.minecraft.world.entity.projectile.ProjectileUtil.getHitResultOnMoveVector(hook, hook::canHitEntityPublic)
                .getType() == net.minecraft.world.phys.HitResult.Type.ENTITY, "Expected mature native margin to hit");
        wall.set(true);
        require(bridge.trace(hook) == null, "Classic rod traced through a blocking wall");
        wall.set(false);
        target.collides = false;
        require(bridge.trace(hook) == null, "Classic rod bypassed native projectile eligibility");
        target.collides = true;
        Mockito.doReturn(new net.minecraft.world.phys.AABB(1.7, 64, 0.5, 2.3, 65.8, 1.1)).when(target).getBoundingBox();
        require(bridge.trace(hook) == null, "Classic margin extended beyond 0.3");
        Mockito.doReturn(new net.minecraft.world.phys.AABB(5, 64, -0.3, 5.6, 65.8, 0.3)).when(target).getBoundingBox();
        require(bridge.trace(hook) == null, "Classic trace extended beyond this tick's flight segment");
        System.out.println("Native rod sweep OK: early modern misses reproduced; legacy 0.3 margin, moving target snapshots, fast segment, walls, eligibility and range verified");
    }
}

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
        verifyRetainedRodContact();
        verifyAttackReach();
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
        verifySweepEffects(codec, original);
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

    private static void verifyRetainedRodContact() {
        var hook = Mockito.mock(net.minecraft.world.entity.projectile.FishingHook.class, Mockito.CALLS_REAL_METHODS);
        var target = Mockito.mock(net.minecraft.world.entity.Entity.class);
        var hit = new net.minecraft.world.phys.EntityHitResult(target, net.minecraft.world.phys.Vec3.ZERO);
        var event = new org.bukkit.event.entity.ProjectileHitEvent(Mockito.mock(org.bukkit.entity.FishHook.class),
                Mockito.mock(org.bukkit.entity.Player.class));
        event.setCancelled(true);
        // Exercise the real Paper pre-hit gate, mocking only the external Bukkit dispatch.
        // It must prevent attachment without requiring removal of the actual bobber.
        try (var events = Mockito.mockStatic(org.bukkit.craftbukkit.event.CraftEventFactory.class)) {
            events.when(() -> org.bukkit.craftbukkit.event.CraftEventFactory.callProjectileHitEvent(hook, hit)).thenReturn(event);
            for (int tick : new int[]{1, 2, 20}) {
                hook.tickCount = tick;
                require(hook.preHitTargetOrDeflectSelf(hit) == net.minecraft.world.entity.projectile.ProjectileDeflection.NONE,
                        "Cancelled rod contact entered native hit handling");
                require(hook.hookedIn == null && !hook.isRemoved(), "Cancelled contact attached or removed the retained bobber");
            }
        }
        Mockito.verify(hook, Mockito.never()).setHookedEntity(Mockito.any());
        Mockito.verify(hook, Mockito.never()).pullEntity(Mockito.any());
        System.out.println("Native retained rod OK: repeated cancelled contacts do not attach, remove or pull the bobber/target");
    }

    private static void verifyAttackReach() {
        double reach = net.minecraft.world.entity.ai.attributes.Attributes.ENTITY_INTERACTION_RANGE.value().getDefaultValue();
        require(reach == 3.0, "Core default player interaction range is not 3.0");
        var player = Mockito.mock(net.minecraft.server.level.ServerPlayer.class, call -> switch (call.getMethod().getName()) {
            case "getAttributeValue" -> reach;
            case "isCreative", "isSpectator" -> false;
            case "getEyePosition" -> net.minecraft.world.phys.Vec3.ZERO;
            default -> call.callRealMethod();
        });
        for (var item : List.of(net.minecraft.world.item.ItemStack.EMPTY,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD),
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NETHERITE_AXE))) {
            Mockito.doReturn(item).when(player).getActiveItem();
            var range = player.entityAttackRange();
            require(range.effectiveMaxRange(player) == 3.0f, "Default ordinary melee range changed for " + item);
            require(player.isWithinAttackRange(new net.minecraft.world.phys.AABB(2.99, -0.5, -0.3, 3.59, 1.3, 0.3), 0),
                    "Core rejected ordinary melee at 2.99 blocks");
            require(!player.isWithinAttackRange(new net.minecraft.world.phys.AABB(3.01, -0.5, -0.3, 3.61, 1.3, 0.3), 0),
                    "Core accepted ordinary melee beyond 3.0 without network tolerance");
        }
        System.out.println("Native reach OK: default hand/sword/axe range 3.0; 2.99 accepted, 3.01 rejected with zero tolerance");
    }

    private static void verifySweepEffects(TeamPacketCodec codec, Object team) throws Exception {
        var registered = net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT
                .wrapAsHolder(net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP);
        var direct = net.minecraft.core.Holder.direct(net.minecraft.sounds.SoundEvent.createVariableRangeEvent(
                net.minecraft.resources.Identifier.withDefaultNamespace("entity.player.attack.sweep")));
        var sweep = new net.minecraft.network.protocol.game.ClientboundSoundPacket(registered,
                net.minecraft.sounds.SoundSource.PLAYERS, 1, 64, 2, 1, 1, 1);
        var directSweep = new net.minecraft.network.protocol.game.ClientboundSoundPacket(direct,
                net.minecraft.sounds.SoundSource.MASTER, 1, 64, 2, 0.5f, 1.5f, 2);
        var attached = new net.minecraft.network.protocol.game.ClientboundSoundEntityPacket(registered,
                net.minecraft.sounds.SoundSource.PLAYERS, Mockito.mock(net.minecraft.world.entity.Entity.class), 1, 1, 3);
        var hurt = new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                net.minecraft.core.Holder.direct(net.minecraft.sounds.SoundEvents.PLAYER_HURT),
                net.minecraft.sounds.SoundSource.PLAYERS, 1, 64, 2, 1, 1, 4);
        var critical = new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                net.minecraft.core.Holder.direct(net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_CRIT),
                net.minecraft.sounds.SoundSource.PLAYERS, 1, 64, 2, 1, 1, 5);
        var rod = new net.minecraft.network.protocol.game.ClientboundSoundPacket(net.minecraft.core.Holder.direct(
                net.minecraft.sounds.SoundEvent.createVariableRangeEvent(
                        net.minecraft.resources.Identifier.withDefaultNamespace("entity.fishing_bobber.throw"))),
                net.minecraft.sounds.SoundSource.PLAYERS, 1, 64, 2, 1, 1, 6);
        var sweepParticle = new net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket(
                net.minecraft.core.particles.ParticleTypes.SWEEP_ATTACK, true, true, 1, 64, 2, 0, 0, 0, 0, 1);
        var criticalParticle = new net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket(
                net.minecraft.core.particles.ParticleTypes.CRIT, false, false, 1, 64, 2, 0, 0, 0, 0, 5);
        var damageParticle = new net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket(
                net.minecraft.core.particles.ParticleTypes.DAMAGE_INDICATOR, false, false, 1, 64, 2, 0, 0, 0, 0, 5);
        var enabled = new AtomicBoolean(true);
        var state = new CollisionTeams<Object>("Alice", "mkb_sound", codec.noCollision(null), codec::noCollision);
        var channel = new EmbeddedChannel();
        var overlay = new ClientCollisions.Overlay(channel, state, codec, enabled::get,
                warning -> { throw new IllegalStateException(warning); });
        channel.pipeline().addLast("overlay", overlay);
        overlay.refresh(); channel.runPendingTasks();
        while (channel.readOutbound() != null) { }
        var allMuted = new net.minecraft.network.protocol.game.ClientboundBundlePacket(List.of(sweep, directSweep, attached, sweepParticle));
        for (Object packet : List.of(sweep, directSweep, attached, sweepParticle, allMuted)) {
            var promise = channel.newPromise();
            channel.writeAndFlush(packet, promise); channel.runPendingTasks();
            require(promise.isSuccess(), "Muted sound write promise was not completed");
            require(channel.readOutbound() == null, "Classic sweep sound escaped suppression");
        }
        for (Object packet : List.of(hurt, critical, rod, criticalParticle, damageParticle)) {
            channel.writeOutbound(packet);
            require(channel.readOutbound() == packet, "Unrelated combat sound changed");
        }
        var mixed = new net.minecraft.network.protocol.game.ClientboundBundlePacket(List.of(sweep, sweepParticle, hurt, criticalParticle,
                (net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket) team));
        channel.writeOutbound(mixed);
        var filtered = (net.minecraft.network.protocol.game.ClientboundBundlePacket) channel.readOutbound();
        var children = new ArrayList<Object>(); filtered.subPackets().forEach(children::add);
        require(children.size() == 3 && children.getFirst() == hurt && children.get(1) == criticalParticle,
                "Mixed bundle lost unrelated sound/particles or packet order");
        Object parameters = codec.decode(children.getLast()).parameters();
        require(parameters.getClass().getMethod("getCollisionRule").invoke(parameters).toString().equals("NEVER"),
                "Mixed sound/team bundle broke classic collision rules");
        var originalChildren = new ArrayList<Object>(); mixed.subPackets().forEach(originalChildren::add);
        require(originalChildren.size() == 5 && originalChildren.getFirst() == sweep, "Shared effect bundle was mutated");
        enabled.set(false); overlay.refresh(); channel.runPendingTasks();
        while (channel.readOutbound() != null) { }
        for (Object packet : List.of(sweep, directSweep, attached, sweepParticle)) {
            channel.writeOutbound(packet);
            require(channel.readOutbound() == packet, "Modern/excluded viewer lost sweep sound");
        }
        enabled.set(true); overlay.refresh(); channel.runPendingTasks();
        while (channel.readOutbound() != null) { }
        overlay.remove(); channel.runPendingTasks();
        while (channel.readOutbound() != null) { }
        channel.writeOutbound(sweep);
        require(channel.readOutbound() == sweep, "Sweep sound not restored after handler removal");
        channel.writeOutbound(sweepParticle);
        require(channel.readOutbound() == sweepParticle, "Sweep particles not restored after handler removal");
        channel.finishAndReleaseAll();
        System.out.println("Native sweep effects OK: sweep sound/particles hidden; mixed bundles, promises, unrelated effects and mode/disable restoration passed");
    }

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

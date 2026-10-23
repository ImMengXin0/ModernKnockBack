package cn.mx.modernkb.combat;

import cn.mx.modernkb.api.*;
import cn.mx.modernkb.config.*;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.*;
import org.bukkit.util.Vector;

public final class KnockbackListener implements Listener {
    private final Supplier<Settings> settings;
    private final PlayerStore players;
    private final CombatTracker tracker;
    private final BooleanSupplier sync;
    private final ClassicCombat classic;
    private final LongAdder handled = new LongAdder();
    private final LongAdder nativeHandled = new LongAdder();
    private final LongAdder nativeFallbacks = new LongAdder();
    private final LongAdder nativeMismatches = new LongAdder();
    public KnockbackListener(Supplier<Settings> settings, PlayerStore players, CombatTracker tracker, BooleanSupplier sync) {
        this(settings, players, tracker, sync, null);
    }
    public KnockbackListener(Supplier<Settings> settings, PlayerStore players, CombatTracker tracker, BooleanSupplier sync, ClassicCombat classic) {
        this.settings = settings; this.players = players; this.tracker = tracker; this.sync = sync;
        this.classic = classic;
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void knockback(EntityKnockbackEvent event) {
        Settings config = settings.get();
        if (!config.enabled() || !(event.getEntity() instanceof LivingEntity victim) || !CombatTracker.owns(victim)) return;
        CombatantKind kind = tracker.kind(victim);
        if (!config.affectOthers() && kind != CombatantKind.PLAYER && kind != CombatantKind.BOT) return;
        var preference = players.get(victim.getUniqueId());
        if (preference.excluded()) return;
        HitContext context = tracker.context(victim,
                event instanceof EntityPushedByEntityAttackEvent attack ? attack.getPushedBy() : null,
                event.getCause().name(), config.mode() == CombatMode.MODERN && config.preserveSyncY() && sync.getAsBoolean());
        Vector original = event.getKnockback();
        if (!KnockbackMath.finite(original)) return;
        KnockbackProfileEvent selection = new KnockbackProfileEvent(context, config.select(context, preference.profile()), config.mode());
        Bukkit.getPluginManager().callEvent(selection);
        KnockbackProfile profile = config.profiles().get(selection.getProfile());
        if (selection.isCancelled() || profile == null || !profile.enabled()) return;
        ClassicCombat.Calculation legacy = classic == null ? null : classic.calculate(event, context, profile, config);
        if (event.isCancelled()) return;
        NativeKnockbackMath.Result calculation;
        if (legacy != null) calculation = new NativeKnockbackMath.Result(legacy.impulse(), false, "classic-vector");
        else if (profile.nativeSettings().enabled()) {
            if (NativeKnockbackMath.supports(event, context)) {
                double strength = event instanceof com.destroystokyo.paper.event.entity.EntityKnockbackByEntityEvent attack
                        ? attack.getKnockbackStrength() : Double.NaN;
                calculation = NativeKnockbackMath.calculate(original, victim.getVelocity(), strength, context, profile);
            } else calculation = NativeKnockbackMath.fallback(original, context, profile, "unsupported-event");
        } else calculation = NativeKnockbackMath.fallback(original, context, profile, "");
        var modification = new KnockbackModifyEvent(context, selection.getProfile(), original, calculation.impulse(),
                calculation.nativeApplied(), calculation.fallbackReason(), config.mode());
        Bukkit.getPluginManager().callEvent(modification);
        if (modification.isCancelled() || event.isCancelled() || settings.get() != config) return;
        Vector result = modification.getImpulse();
        if (context.syncOwnsVertical()) result.setY(original.getY());
        event.setKnockback(result);
        if (legacy != null) classic.prepare(event, context, config, legacy);
        handled.increment();
        if (calculation.nativeApplied()) nativeHandled.increment();
        else if (legacy == null && profile.nativeSettings().enabled()) {
            nativeFallbacks.increment();
            if (!calculation.fallbackReason().equals("unsupported-event")) nativeMismatches.increment();
        }
    }
    public long handled() { return handled.sum(); }
    public long nativeHandled() { return nativeHandled.sum(); }
    public long nativeFallbacks() { return nativeFallbacks.sum(); }
    public long nativeMismatches() { return nativeMismatches.sum(); }
}

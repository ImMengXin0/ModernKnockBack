package cn.mx.modernkb.api;

import org.bukkit.event.Event;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.util.Vector;

/** Victim-region event. Vectors are IMPULSES, not final velocity; clones must be written back. */
public final class KnockbackModifyEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final HitContext context;
    private final String profile;
    private final Vector original;
    private final boolean nativeCalculation;
    private final String fallbackReason;
    private final CombatMode mode;
    private Vector impulse;
    private boolean cancelled;
    public KnockbackModifyEvent(HitContext context, String profile, Vector original, Vector impulse) {
        this(context, profile, original, impulse, false, "");
    }
    public KnockbackModifyEvent(HitContext context, String profile, Vector original, Vector impulse,
                                boolean nativeCalculation, String fallbackReason) {
        this(context, profile, original, impulse, nativeCalculation, fallbackReason, CombatMode.MODERN);
    }
    public KnockbackModifyEvent(HitContext context, String profile, Vector original, Vector impulse,
                                boolean nativeCalculation, String fallbackReason, CombatMode mode) {
        this.context = context; this.profile = profile; this.original = original.clone(); setImpulse(impulse);
        this.nativeCalculation = nativeCalculation; this.fallbackReason = fallbackReason;
        this.mode = mode;
    }
    public CombatMode getMode() { return mode; }
    public boolean isNativeCalculation() { return nativeCalculation; }
    public String getFallbackReason() { return fallbackReason; }
    public HitContext getContext() { return context; }
    public String getProfile() { return profile; }
    public Vector getOriginalImpulse() { return original.clone(); }
    public Vector getImpulse() { return impulse.clone(); }
    public void setImpulse(Vector value) {
        if (value == null || !Double.isFinite(value.getX()) || !Double.isFinite(value.getY()) || !Double.isFinite(value.getZ()))
            throw new IllegalArgumentException("Impulse must be finite");
        impulse = value.clone();
    }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

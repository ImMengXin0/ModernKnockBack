package cn.mx.modernkb.api;

import org.bukkit.event.Event;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import java.util.Objects;

/** Victim-region event. Cancelling leaves the native knockback unchanged. */
public final class KnockbackProfileEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final HitContext context;
    private final CombatMode mode;
    private String profile;
    private boolean cancelled;
    public KnockbackProfileEvent(HitContext context, String profile) { this(context, profile, CombatMode.MODERN); }
    public KnockbackProfileEvent(HitContext context, String profile, CombatMode mode) { this.context = context; this.profile = profile; this.mode = mode; }
    public CombatMode getMode() { return mode; }
    public HitContext getContext() { return context; }
    public String getProfile() { return profile; }
    public void setProfile(String profile) { this.profile = Objects.requireNonNull(profile); }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

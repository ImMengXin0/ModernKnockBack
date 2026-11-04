package fairy.kbvote.qa;

import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.plugin.RegisteredListener;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import java.util.*;

/** QA-only timing wrappers. Original priority, order and cancellation handling are preserved. */
final class FirstHitProbe implements AutoCloseable {
    private record Wrapped(HandlerList list, RegisteredListener original, RegisteredListener wrapper) {}
    private final List<Wrapped> installed = new ArrayList<>();
    private final Map<String, Long> nanos = new LinkedHashMap<>();
    private boolean measuring;

    FirstHitProbe() {
        Set<HandlerList> lists = new LinkedHashSet<>(List.of(PrePlayerAttackEntityEvent.getHandlerList(),
                EntityDamageEvent.getHandlerList(), EntityKnockbackEvent.getHandlerList(), PlayerVelocityEvent.getHandlerList()));
        for (HandlerList list : lists) {
            RegisteredListener[] snapshot = list.getRegisteredListeners();
            for (RegisteredListener original : snapshot) list.unregister(original);
            for (RegisteredListener original : snapshot) {
                RegisteredListener wrapper = new RegisteredListener(original.getListener(), (ignored, event) -> {
                    if (!measuring) { original.callEvent(event); return; }
                    long start = System.nanoTime();
                    try { original.callEvent(event); }
                    finally {
                        long duration = System.nanoTime() - start;
                        String key = original.getPlugin().getName() + "/" + original.getListener().getClass().getSimpleName()
                                + "/" + event.getEventName() + "/" + original.getPriority();
                        nanos.merge(key, duration, Long::sum);
                    }
                }, original.getPriority(), original.getPlugin(), false);
                installed.add(new Wrapped(list, original, wrapper)); list.register(wrapper);
            }
        }
    }
    void begin() { nanos.clear(); measuring = true; }
    String finish() {
        measuring = false;
        return nanos.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed())
                .limit(12).map(e -> e.getKey() + "=" + String.format(Locale.ROOT, "%.3fms", e.getValue() / 1e6)).toList().toString();
    }
    @Override public void close() {
        measuring = false;
        for (Wrapped value : installed) value.list.unregister(value.wrapper);
        for (Wrapped value : installed) value.list.register(value.original);
        installed.clear();
    }
}

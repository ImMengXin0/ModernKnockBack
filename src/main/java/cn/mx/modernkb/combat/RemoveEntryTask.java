package cn.mx.modernkb.combat;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Map;
import java.util.function.Consumer;

/** Same conditional cleanup for the executed and retired entity-scheduler paths. */
record RemoveEntryTask<K, V>(Map<K, V> entries, K key, V expected)
        implements Consumer<ScheduledTask>, Runnable {
    @Override public void accept(ScheduledTask ignored) { run(); }
    @Override public void run() { entries.remove(key, expected); }
}

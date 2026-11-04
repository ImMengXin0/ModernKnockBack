package cn.mx.modernkb.combat;

import java.util.concurrent.ConcurrentHashMap;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoveEntryTaskTest {
    @Test public void constructionDoesNotRemoveAndRunIsIdempotent() {
        var map = new ConcurrentHashMap<String, Object>(); var value = new Object(); map.put("p", value);
        var task = new RemoveEntryTask<>(map, "p", value);
        assertSame(value, map.get("p")); task.run(); task.run(); assertTrue(map.isEmpty());
    }
    @Test public void executedCallbackUsesSameConditionalRemoval() {
        var map = new ConcurrentHashMap<String, Object>(); var value = new Object(); map.put("p", value);
        new RemoveEntryTask<>(map, "p", value).accept(null); assertTrue(map.isEmpty());
    }
    @Test public void oldCallbackNeverRemovesNewHit() {
        var map = new ConcurrentHashMap<String, Object>(); var old = new Object(); var current = new Object();
        map.put("p", old); var task = new RemoveEntryTask<>(map, "p", old); map.put("p", current);
        task.run(); task.accept(null); assertSame(current, map.get("p"));
    }
}

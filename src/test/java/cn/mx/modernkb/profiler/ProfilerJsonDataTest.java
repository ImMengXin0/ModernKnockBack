package cn.mx.modernkb.profiler;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProfilerJsonDataTest {
    @Test public void escapesScriptClosersControlCharactersAndUnicodeSeparators() {
        String result = ProfilerJson.write("</script><img src=x onerror=alert(1)>&\u2028\u2029\n\t\"\\\b\f\r\u0001");
        assertFalse(result.contains("<"));
        assertFalse(result.contains(">"));
        assertFalse(result.contains("&"));
        assertFalse(result.contains("\u2028"));
        assertFalse(result.contains("\u2029"));
        assertTrue(result.contains("\\u003c/script\\u003e"));
        assertTrue(result.contains("\\u2028\\u2029\\n\\t\\\"\\\\\\b\\f\\r\\u0001"));
        assertEquals("\"中文\\ud83d\\ude00\"", ProfilerJson.write("中文😀"));
    }

    @Test public void serializesNestedJsonValuesAndNormalizesNonfiniteNumbers() {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("values", Arrays.asList(1, 2L, new BigDecimal("0.125"), new BigInteger("123456789012345678901"), true, null));
        object.put("bad", List.of(Double.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY));
        assertEquals("{\"values\":[1,2,0.125,123456789012345678901,true,null],\"bad\":[null,null,null]}", ProfilerJson.write(object));
    }

    @Test public void snapshotsMutableNumbersAndContainers() {
        AtomicInteger count = new AtomicInteger(12);
        List<Object> list = new ArrayList<>(List.of(count));
        Object frozen = ProfilerJson.freeze(Map.of("list", list));
        count.set(19);
        list.add(3);
        assertEquals("{\"list\":[12.0]}", ProfilerJson.write(frozen));
    }

    @Test public void rejectsUnknownTypesNonStringKeysCyclesAndExcessiveNesting() {
        assertThrows(IllegalArgumentException.class, () -> ProfilerJson.write(new Object()));
        assertThrows(IllegalArgumentException.class, () -> ProfilerJson.write(Map.of(1, "not string")));
        List<Object> cycle = new ArrayList<>();
        cycle.add(cycle);
        assertThrows(IllegalArgumentException.class, () -> ProfilerJson.write(cycle));
        assertThrows(IllegalArgumentException.class, () -> ProfilerJson.freeze(cycle));
        Object nested = null;
        for (int i = 0; i < 130; i++) nested = Arrays.asList(nested);
        Object excessive = nested;
        assertThrows(IllegalArgumentException.class, () -> ProfilerJson.write(excessive));
    }

    @Test public void sharedSubtreesAreAllowedWithoutBeingMistakenForCycles() {
        var shared = List.of(1, 2);
        assertEquals("[[1,2],[1,2]]", ProfilerJson.write(List.of(shared, shared)));
    }
}

package cn.mx.modernkb.profiler;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small JSON writer for self-contained reports; it does not invoke arbitrary object serializers. */
public final class ProfilerJson {
    private ProfilerJson() {}

    /** The returned JSON is safe to embed as the contents of an HTML script element. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder(4096);
        append(out, value, new IdentityHashMap<>(), 0);
        return out.toString();
    }

    /** Take a defensive, deeply immutable snapshot of the accepted JSON value types. */
    static Object freeze(Object value) {
        return freeze(value, new IdentityHashMap<>(), 0);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> freezeMap(Map<String, ?> value) {
        return (Map<String, Object>) freeze(value);
    }

    private static Object freeze(Object value, IdentityHashMap<Object, Boolean> visiting, int depth) {
        depth(depth);
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Number number) return number(number);
        enter(value, visiting);
        try {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key))
                        throw new IllegalArgumentException("JSON object keys must be strings");
                    copy.put(key, freeze(entry.getValue(), visiting, depth + 1));
                }
                return Collections.unmodifiableMap(copy);
            }
            if (value instanceof List<?> list) {
                List<Object> copy = new ArrayList<>(list.size());
                for (Object item : list) copy.add(freeze(item, visiting, depth + 1));
                return Collections.unmodifiableList(copy);
            }
            throw unsupported(value);
        } finally {
            visiting.remove(value);
        }
    }

    private static void append(StringBuilder out, Object value, IdentityHashMap<Object, Boolean> visiting, int depth) {
        depth(depth);
        if (value == null) { out.append("null"); return; }
        if (value instanceof String text) { quote(out, text); return; }
        if (value instanceof Boolean bool) { out.append(bool); return; }
        if (value instanceof Number n) { out.append(String.valueOf(number(n))); return; }
        enter(value, visiting);
        try {
            if (value instanceof Map<?, ?> map) {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key))
                        throw new IllegalArgumentException("JSON object keys must be strings");
                    if (!first) out.append(',');
                    first = false;
                    quote(out, key);
                    out.append(':');
                    append(out, entry.getValue(), visiting, depth + 1);
                }
                out.append('}');
                return;
            }
            if (value instanceof List<?> list) {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i != 0) out.append(',');
                    append(out, list.get(i), visiting, depth + 1);
                }
                out.append(']');
                return;
            }
            throw unsupported(value);
        } finally {
            visiting.remove(value);
        }
    }

    private static Number number(Number number) {
        if (number instanceof Byte || number instanceof Short || number instanceof Integer || number instanceof Long
                || number instanceof BigInteger || number instanceof BigDecimal) return number;
        double value = number.doubleValue();
        // Snapshot mutable Number implementations too, rather than retaining an AtomicInteger etc.
        return Double.isFinite(value) ? value : null;
    }

    private static void quote(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    // Escaping < also prevents </script> and HTML comment delimiters from ending the data block.
                    if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == '\u2028' || c == '\u2029'
                            || Character.isSurrogate(c)) {
                        out.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) out.append(Character.forDigit((c >>> shift) & 15, 16));
                    } else out.append(c);
                }
            }
        }
        out.append('"');
    }

    private static void enter(Object value, IdentityHashMap<Object, Boolean> visiting) {
        if (visiting.put(value, Boolean.TRUE) != null) throw new IllegalArgumentException("Cyclic JSON value");
    }

    private static void depth(int depth) {
        if (depth > 128) throw new IllegalArgumentException("JSON nesting exceeds 128 levels");
    }

    private static IllegalArgumentException unsupported(Object value) {
        return new IllegalArgumentException("Unsupported JSON value: " + value.getClass().getName());
    }
}

package cn.mx.modernkb.gui;

import cn.mx.modernkb.api.CombatMode;
import cn.mx.modernkb.config.KnockbackProfile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.function.Function;

/** Descriptions, bounds and click steps for values editable from the in-game KB menu. */
public final class GuiParameterCatalog {
    public enum Section {
        CORE("击退与倍率", "基础KB与攻击倍率"),
        ADVANCED("限制与动量", "速度上限、惯性与原生KB"),
        ROD("鱼竿", "鱼钩飞行和命中参数");

        private final String title;
        private final String description;
        Section(String title, String description) { this.title = title; this.description = description; }
        public String title() { return title; }
        public String description() { return description; }
    }
    public enum Kind { NUMBER, BOOLEAN, CHOICE }
    public record Parameter(String path, String label, String explanation, Kind kind,
                            double min, double max, double step, Function<KnockbackProfile, Object> read) {
        public Object value(KnockbackProfile profile) { return read.apply(profile); }
        public Object change(Object current, int direction) {
            if (direction == 0) throw new IllegalArgumentException("Direction cannot be zero");
            if (kind == Kind.BOOLEAN) return !(Boolean) current;
            if (kind == Kind.CHOICE) {
                String value = current.toString();
                if (path.equals("native.air-vertical-mode")) return value.equals("preserve") ? "add" : "preserve";
                throw new IllegalArgumentException("Unsupported choice: " + path);
            }
            double increment = step * Integer.signum(direction);
            double value = Math.clamp(((Number) current).doubleValue() + increment, min, max);
            return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).doubleValue();
        }
        public static String format(Object value) {
            if (value instanceof Number number) {
                return BigDecimal.valueOf(number.doubleValue()).setScale(4, RoundingMode.HALF_UP)
                        .stripTrailingZeros().toPlainString();
            }
            return value.toString();
        }
    }

    private static final List<Parameter> CLASSIC_CORE = List.of(
            bool("enabled", "启用当前配置", "关闭后，本 profile 的 KB 参数不参与修改。", KnockbackProfile::enabled),
            number("classic.horizontal-ground", "地面水平KB", "目标水平推力；不含疾跑和击退附魔附加。", 0, 4, .05, p -> p.classic().horizontalGround()),
            number("classic.horizontal-air", "空中水平KB", "目标水平推力；不含疾跑和击退附魔附加。", 0, 4, .05, p -> p.classic().horizontalAir()),
            number("classic.vertical-ground", "地面垂直KB", "地面命中时的基础Y速度。", 0, 4, .025, p -> p.classic().verticalGround()),
            number("classic.vertical-air", "空中垂直KB", "空中命中时的基础Y速度。", 0, 4, .025, p -> p.classic().verticalAir()),
            number("classic.sprint-horizontal", "疾跑水平附加", "疾跑命中额外增加的水平速度。", 0, 4, .05, p -> p.classic().sprintHorizontal()),
            number("classic.sprint-vertical", "疾跑垂直附加", "有疾跑或击退附魔水平附加时增加的Y速度。", 0, 4, .025, p -> p.classic().sprintVertical()),
            number("classic.enchantment-horizontal", "击退附魔每级", "每级击退附魔增加的水平速度。", 0, 4, .05, p -> p.classic().enchantmentHorizontal()));
    private static final List<Parameter> CLASSIC_ADVANCED = List.of(
            number("classic.horizontal-retention", "水平惯性保留", "命中前水平速度保留比例；0表示覆盖旧水平动量。", 0, 1, .05, p -> p.classic().horizontalRetention()),
            number("classic.vertical-retention", "垂直惯性保留", "命中前垂直速度保留比例。", 0, 1, .05, p -> p.classic().verticalRetention()),
            number("classic.vertical-max", "向上速度上限", "Classic 输出的Y速度最高值。", 0, 4, .025, p -> p.classic().verticalMax()),
            bool("classic.respect-resistance", "应用其他抗性", "控制 Classic 是否缩放自定义/其他抗击退属性；下界合金护甲自身抗性始终忽略。", p -> p.classic().respectResistance()),
            bool("classic.y-limit-enabled", "启用高度限制", "超过最大离地高度后使用下方的垂直速度；只作用于近战。", p -> p.classic().yLimit()),
            number("classic.max-height", "高度限制值", "离地超过此值时触发垂直速度限制，单位为方块。", 0, 8, .05, p -> p.classic().maxHeight()),
            number("classic.vertical-after-limit", "超高后Y速度", "触发高度限制时使用的Y速度。", -4, 0, .025, p -> p.classic().verticalAfterLimit()));
    private static final List<Parameter> MODERN_CORE = List.of(
            bool("enabled", "启用当前配置", "关闭后，本 profile 的 KB 参数不参与修改。", KnockbackProfile::enabled),
            number("horizontal.ground", "地面水平倍率", "地面击退水平增量的倍率，1.0保持原值。", 0, 4, .05, KnockbackProfile::groundHorizontal),
            number("horizontal.air", "空中水平倍率", "空中击退水平增量的倍率，1.0保持原值。", 0, 4, .05, KnockbackProfile::airHorizontal),
            number("vertical.ground", "地面垂直倍率", "地面击退垂直增量的倍率，1.0保持原值。", 0, 4, .05, KnockbackProfile::groundVertical),
            number("vertical.air", "空中垂直倍率", "空中击退垂直增量的倍率，1.0保持原值。", 0, 4, .05, KnockbackProfile::airVertical),
            number("modern.sprint-multiplier", "疾跑倍率", "疾跑近战击退增量的额外倍率。", 0, 4, .05, KnockbackProfile::sprintMultiplier),
            number("modern.critical-multiplier", "暴击倍率", "暴击近战击退增量的额外倍率。", 0, 4, .05, KnockbackProfile::criticalMultiplier),
            number("modern.weak-hit-multiplier", "未满蓄力倍率", "未满蓄力攻击增量从0到1.0平滑插值。", 0, 4, .05, KnockbackProfile::weakMultiplier));
    private static final List<Parameter> MODERN_ADVANCED = List.of(
            number("limits.horizontal", "水平增量上限", "KB水平增量上限；0表示不额外限制。", 0, 10, .1, KnockbackProfile::maxHorizontal),
            number("limits.upward", "向上增量上限", "KB向上增量上限；0表示不额外限制。", 0, 10, .1, KnockbackProfile::maxUpward),
            number("limits.downward", "向下增量上限", "KB向下增量绝对值上限；0表示不额外限制。", 0, 10, .1, KnockbackProfile::maxDownward),
            bool("native.enabled", "原生公式模式", "匹配服务器原生攻击公式时，按新增推力参数计算；否则安全回退。", p -> p.nativeSettings().enabled()),
            number("native.horizontal-retention-ground", "原生地面水平保留", "原生公式模式下，命中前水平动量保留比例。", 0, 1, .05, p -> p.nativeSettings().groundHorizontalRetention()),
            number("native.horizontal-retention-air", "原生空中水平保留", "原生公式模式下，命中前水平动量保留比例。", 0, 1, .05, p -> p.nativeSettings().airHorizontalRetention()),
            number("native.vertical-retention-ground", "原生地面Y保留", "原生公式模式下，命中前Y速度保留比例。", 0, 1, .05, p -> p.nativeSettings().groundVerticalRetention()),
            number("native.vertical-max-ground", "原生地面Y上限", "原生公式模式下，地面击退后的最高Y速度。", 0, 4, .025, p -> p.nativeSettings().groundVerticalMax()),
            choice("native.air-vertical-mode", "原生空中Y模式", "preserve保留原生垂直速度；add使用配置的空中Y上限。", p -> p.nativeSettings().addAirVertical() ? "add" : "preserve"),
            number("native.vertical-max-air", "原生空中Y上限", "空中Y模式为add时使用的最高Y速度。", 0, 4, .025, p -> p.nativeSettings().airVerticalMax()));
    private static final List<Parameter> ROD = List.of(
            bool("rod.enabled", "启用鱼竿控制", "关闭后鱼竿轨迹和Classic鱼竿KB恢复原版处理。", p -> p.rod().enabled()),
            integer("rod.cast-interval-ticks", "出杆间隔", "两次投出鱼钩之间的最小间隔，20 tick约1秒。", 0, 1200, p -> p.rod().castTicks()),
            integer("rod.recast-delay-ticks", "收杆后间隔", "收回鱼钩后到下一次出杆的间隔。", 0, 1200, p -> p.rod().recastTicks()),
            number("rod.horizontal-speed-multiplier", "鱼钩水平速度", "鱼钩投出时水平方向速度倍率，1.0为原版。", 0, 4, .05, p -> p.rod().horizontalSpeed()),
            number("rod.vertical-speed-multiplier", "鱼钩垂直速度", "鱼钩投出时垂直方向速度倍率，1.0为原版。", 0, 4, .05, p -> p.rod().verticalSpeed()),
            number("rod.gravity-multiplier", "鱼钩下落速度", "鱼钩飞行中的重力倍率，1.0为原版。", 1, 4, .1, p -> p.rod().gravityMultiplier()),
            number("rod.max-distance", "最大距离", "鱼钩从抛出位置起允许的最大飞行距离；0不额外限制。", 0, 32, .5, p -> p.rod().maxDistance()),
            integer("rod.max-lifetime-ticks", "最大飞行时间", "飞行寿命上限tick；0不额外限制。", 0, 12000, p -> p.rod().lifetimeTicks()),
            number("rod.impact-strength", "鱼竿命中强度", "鱼竿命中KB强度；Classic下0.4为完整向量。", 0, 2, .05, p -> p.rod().impactStrength()));

    private GuiParameterCatalog() {}
    public static List<Section> sections() { return List.of(Section.values()); }
    public static List<Parameter> parameters(CombatMode mode, Section section) {
        if (mode == CombatMode.CLASSIC) return switch (section) {
            case CORE -> CLASSIC_CORE;
            case ADVANCED -> CLASSIC_ADVANCED;
            case ROD -> ROD;
        };
        return switch (section) {
            case CORE -> MODERN_CORE;
            case ADVANCED -> MODERN_ADVANCED;
            case ROD -> ROD;
        };
    }
    public static List<String> editablePaths() {
        return java.util.stream.Stream.of(CLASSIC_CORE, CLASSIC_ADVANCED, MODERN_CORE, MODERN_ADVANCED, ROD)
                .flatMap(List::stream)
                .map(Parameter::path).distinct().toList();
    }
    private static Parameter number(String path, String label, String explanation, double min, double max, double step,
                                    Function<KnockbackProfile, Object> read) {
        return new Parameter(path, label, explanation, Kind.NUMBER, min, max, step, read);
    }
    private static Parameter integer(String path, String label, String explanation, double max, double step,
                                     Function<KnockbackProfile, Object> read) {
        return number(path, label, explanation, 0, max, step, read);
    }
    private static Parameter bool(String path, String label, String explanation, Function<KnockbackProfile, Object> read) {
        return new Parameter(path, label, explanation, Kind.BOOLEAN, 0, 1, 1, read);
    }
    private static Parameter choice(String path, String label, String explanation, Function<KnockbackProfile, Object> read) {
        return new Parameter(path, label, explanation, Kind.CHOICE, 0, 1, 1, read);
    }
}

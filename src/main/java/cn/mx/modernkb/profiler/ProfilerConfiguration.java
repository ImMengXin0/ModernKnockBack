package cn.mx.modernkb.profiler;

import cn.mx.modernkb.config.ClassicSettings;
import cn.mx.modernkb.config.KnockbackProfile;
import cn.mx.modernkb.config.Settings;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Effective runtime values, including inherited profile fields, without reading configuration files. */
public final class ProfilerConfiguration {
    private ProfilerConfiguration() {}

    public static Map<String, Object> snapshot(Settings settings) {
        Map<String, Object> values = new LinkedHashMap<>();
        append(values, "", Objects.requireNonNull(settings), new IdentityHashMap<>());
        return Collections.unmodifiableMap(values);
    }

    private static void append(Map<String, Object> values, String prefix, Settings settings,
                               IdentityHashMap<Settings, Boolean> visiting) {
        if (visiting.put(settings, Boolean.TRUE) != null) throw new IllegalArgumentException("Cyclic item combat settings");
        try {
            values.put(prefix + "mode.yml.mode", settings.mode().key());
            values.put(prefix + "config.yml.config-version", 1);
            values.put(prefix + "config.yml.enabled", settings.enabled());
            values.put(prefix + "config.yml.affect-other-living-entities", settings.affectOthers());
            values.put(prefix + "config.yml.respect-player-profile", settings.respectPlayerProfile());
            values.put(prefix + "config.yml.compatibility.knockbacksync", settings.preserveSyncY() ? "preserve-y" : "before-sync");
            values.put(prefix + "config.yml.bot-metadata", settings.botMetadata().stream().sorted().toList());
            settings.routes().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> values.put(prefix + "config.yml.profiles." + e.getKey(), e.getValue()));
            settings.matchups().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> values.put(prefix + "config.yml.matchups." + e.getKey(), e.getValue()));
            ClassicSettings c = settings.classic();
            String combat = prefix + "combat.yml.";
            values.put(combat + "hit-interval-ticks", c.hitIntervalTicks());
            values.put(combat + "rod-hit-interval-ticks", c.rodHitIntervalTicks());
            values.put(combat + "rod-melee-interval-ticks", c.rodMeleeIntervalTicks());
            values.put(combat + "disable-sweep", c.disableSweep());
            values.put(combat + "protect-velocity-from-sync", c.protectVelocityFromSync());
            values.put(combat + "stop_sprint", c.stopSprint());
            values.put(combat + "jump-reset-enabled", c.jumpResetEnabled());
            values.put(combat + "jump-reset-window-ticks", c.jumpResetWindowTicks());
            values.put(combat + "click-density-enabled", c.clickDensityEnabled());
            values.put(combat + "legacy-hit-effects", c.legacyHitEffects());
            values.put(combat + "legacy-axe-damage", c.legacyAxeDamage());
            values.put(prefix + "effective.classic.rod-hit-interval-ticks", c.effectiveRodHitTicks());
            values.put(prefix + "effective.classic.rod-melee-interval-ticks", c.effectiveRodMeleeTicks());
            settings.profiles().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> profile(values, prefix + "profiles.yml.profiles." + e.getKey() + ".", e.getValue()));
            values.put(prefix + "item.yml.modern-items", settings.items().modernItems().stream().sorted().toList());
            values.put(prefix + "effective.item-modern.loaded", settings.items().modern() != null);
            if (settings.items().modern() != null) append(values, prefix + "item-modern.", settings.items().modern(), visiting);
        } finally {
            visiting.remove(settings);
        }
    }

    private static void profile(Map<String, Object> values, String prefix, KnockbackProfile p) {
        values.put(prefix + "enabled", p.enabled());
        values.put(prefix + "horizontal.ground", p.groundHorizontal());
        values.put(prefix + "horizontal.air", p.airHorizontal());
        values.put(prefix + "vertical.ground", p.groundVertical());
        values.put(prefix + "vertical.air", p.airVertical());
        values.put(prefix + "modern.sprint-multiplier", p.sprintMultiplier());
        values.put(prefix + "modern.critical-multiplier", p.criticalMultiplier());
        values.put(prefix + "modern.weak-hit-multiplier", p.weakMultiplier());
        values.put(prefix + "limits.horizontal", p.maxHorizontal());
        values.put(prefix + "limits.upward", p.maxUpward());
        values.put(prefix + "limits.downward", p.maxDownward());
        var r = p.rod();
        values.put(prefix + "rod.enabled", r.enabled());
        values.put(prefix + "rod.cast-interval-ticks", r.castTicks());
        values.put(prefix + "rod.recast-delay-ticks", r.recastTicks());
        values.put(prefix + "rod.horizontal-speed-multiplier", r.horizontalSpeed());
        values.put(prefix + "rod.vertical-speed-multiplier", r.verticalSpeed());
        values.put(prefix + "rod.gravity-multiplier", r.gravityMultiplier());
        values.put(prefix + "rod.max-distance", r.maxDistance());
        values.put(prefix + "rod.max-lifetime-ticks", r.lifetimeTicks());
        values.put(prefix + "rod.impact-strength", r.impactStrength());
        var n = p.nativeSettings();
        values.put(prefix + "native.enabled", n.enabled());
        values.put(prefix + "native.horizontal-retention-ground", n.groundHorizontalRetention());
        values.put(prefix + "native.horizontal-retention-air", n.airHorizontalRetention());
        values.put(prefix + "native.vertical-retention-ground", n.groundVerticalRetention());
        values.put(prefix + "native.vertical-max-ground", n.groundVerticalMax());
        values.put(prefix + "native.air-vertical-mode", n.addAirVertical() ? "add" : "preserve");
        values.put(prefix + "native.vertical-max-air", n.airVerticalMax());
        var c = p.classic();
        values.put(prefix + "classic.horizontal-ground", c.horizontalGround());
        values.put(prefix + "classic.horizontal-air", c.horizontalAir());
        values.put(prefix + "classic.vertical-ground", c.verticalGround());
        values.put(prefix + "classic.vertical-air", c.verticalAir());
        values.put(prefix + "classic.horizontal-retention", c.horizontalRetention());
        values.put(prefix + "classic.vertical-retention", c.verticalRetention());
        values.put(prefix + "classic.sprint-horizontal", c.sprintHorizontal());
        values.put(prefix + "classic.sprint-vertical", c.sprintVertical());
        values.put(prefix + "classic.enchantment-horizontal", c.enchantmentHorizontal());
        values.put(prefix + "classic.vertical-max", c.verticalMax());
        values.put(prefix + "classic.respect-resistance", c.respectResistance());
        values.put(prefix + "classic.y-limit-enabled", c.yLimit());
        values.put(prefix + "classic.max-height", c.maxHeight());
        values.put(prefix + "classic.vertical-after-limit", c.verticalAfterLimit());
    }

    /** Definitions use YAML field names; '*' represents any effective profile name. */
    public static Map<String, String> descriptions() {
        return DESCRIPTIONS;
    }

    static Map<String, String> descriptions(Map<String, Object> values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : values.keySet()) result.put(key, description(key));
        return Collections.unmodifiableMap(result);
    }

    public static String description(String key) {
        String normalized = key;
        while (normalized.startsWith("item-modern.")) normalized = normalized.substring("item-modern.".length());
        if (normalized.startsWith("profiles.yml.profiles.")) {
            int nameEnd = normalized.indexOf('.', "profiles.yml.profiles.".length());
            if (nameEnd >= 0) normalized = "profiles.yml.profiles.*" + normalized.substring(nameEnd);
        }
        if (normalized.startsWith("config.yml.profiles.")) return "命中类型对应的默认 KB profile；个人配置与 matchup 的优先级由运行时规则决定。";
        if (normalized.startsWith("config.yml.matchups.")) return "指定攻击类型、攻击者类别和受击者类别的 KB profile，优先于普通类型路由。";
        return DESCRIPTIONS.getOrDefault(normalized, "运行时已经验证并生效的参数。" );
    }

    private static final Map<String, String> DESCRIPTIONS;
    static {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("mode.yml.mode", "该快照的战斗模式；Classic 使用目标速度，Modern 使用现代击退增量或经验证的原生公式。");
        d.put("config.yml.config-version", "配置结构版本。");
        d.put("config.yml.enabled", "该模式的 KB 修改总开关。");
        d.put("config.yml.affect-other-living-entities", "是否修改玩家与 Bot 以外的生物击退。");
        d.put("config.yml.respect-player-profile", "是否允许玩家个人分配的 profile 覆盖普通命中类型路由。");
        d.put("config.yml.compatibility.knockbacksync", "preserve-y 保留同步插件控制的 Y；before-sync 在同步插件处理前应用本插件参数。");
        d.put("config.yml.bot-metadata", "识别 Bot 的 metadata 标记列表。");
        d.put("combat.yml.hit-interval-ticks", "Classic 同一受击者共享的近战受击间隔；20 TPS 下每 tick 为 50ms。");
        d.put("combat.yml.rod-hit-interval-ticks", "Classic 鱼竿受击保护配置；实际不会短于近战受击间隔。");
        d.put("combat.yml.rod-melee-interval-ticks", "Classic 鱼竿命中后转近战的保护配置；实际取三个受击间隔的最大值。");
        d.put("combat.yml.disable-sweep", "Classic 是否关闭横扫攻击与对应横扫效果。");
        d.put("combat.yml.protect-velocity-from-sync", "Classic 是否在速度事件中保护本次已经计算出的速度，避免同步插件再次改写。");
        d.put("combat.yml.stop_sprint", "false 使用客户端 START/STOP 疾跑状态；true 接受服务端攻击后的原生疾跑重置。");
        d.put("combat.yml.jump-reset-enabled", "Classic 是否在已确认的主动跳跃窗口内保留部分旧动量；反向抵消仍受保护下限约束。");
        d.put("combat.yml.jump-reset-window-ticks", "Classic 已确认主动跳跃后允许 jump reset 的服务器 tick 窗口。");
        d.put("combat.yml.click-density-enabled", "Classic 是否允许当前或前一 tick 的密集实体攻击尝试适度减少自身水平退速；收益封顶，不增加伤害频率。");
        d.put("combat.yml.legacy-hit-effects", "Classic 使用旧版受击观感，过滤现代强弱击提示音和伤害指示粒子，保留正常受击反馈。");
        d.put("combat.yml.legacy-axe-damage", "Classic 对适用的普通斧头使用 1.8 基础伤害；自定义攻击属性等例外仍保留原值。");
        d.put("effective.classic.rod-hit-interval-ticks", "实际鱼竿受击保护 tick，取近战与鱼竿受击间隔的最大值；仅 Classic 生效。");
        d.put("effective.classic.rod-melee-interval-ticks", "实际竿接刀保护 tick，取近战、鱼竿、竿接刀三项的最大值；仅 Classic 生效。");
        d.put("item.yml.modern-items", "Classic 中改用现代攻击方式及 Modern KB 配置的物品列表。");
        d.put("effective.item-modern.loaded", "是否存在同批次加载的 Modern 物品例外配置；相关值以 item-modern. 前缀完整列出。");
        String p = "profiles.yml.profiles.*.";
        d.put(p + "enabled", "是否启用该 profile 的 KB 修改；此处列出的是继承 defaults 后的有效值。");
        d.put(p + "horizontal.ground", "Modern 地面水平击退增量倍率，1.0 保持原值；不是移动距离。");
        d.put(p + "horizontal.air", "Modern 空中水平击退增量倍率，1.0 保持原值。");
        d.put(p + "vertical.ground", "Modern 地面垂直击退增量倍率。");
        d.put(p + "vertical.air", "Modern 空中垂直击退增量倍率。");
        d.put(p + "modern.sprint-multiplier", "Modern 疾跑近战额外击退倍率。");
        d.put(p + "modern.critical-multiplier", "Modern 暴击近战额外击退倍率。");
        d.put(p + "modern.weak-hit-multiplier", "Modern 未满蓄力攻击的倍率端点，按蓄力比例向 1.0 插值。");
        d.put(p + "limits.horizontal", "Modern 水平击退增量上限，0 为不额外限制。");
        d.put(p + "limits.upward", "Modern 向上击退增量上限，0 为不额外限制。");
        d.put(p + "limits.downward", "Modern 向下击退增量绝对值上限，0 为不额外限制。");
        d.put(p + "rod.enabled", "启用鱼钩轨迹控制及 Classic 鱼竿 KB；关闭时恢复原版处理。");
        d.put(p + "rod.cast-interval-ticks", "相邻两次投出鱼钩的最小服务器 tick 间隔。");
        d.put(p + "rod.recast-delay-ticks", "收杆后允许下一次投出鱼钩的等待 tick。");
        d.put(p + "rod.horizontal-speed-multiplier", "鱼钩初始水平速度倍率，1.0 为原版。");
        d.put(p + "rod.vertical-speed-multiplier", "鱼钩初始垂直速度倍率，1.0 为原版。");
        d.put(p + "rod.gravity-multiplier", "鱼钩飞行中的重力倍率，1.0 为原版，越大下落越快。");
        d.put(p + "rod.max-distance", "从抛出位置起允许的最大鱼钩飞行距离，单位为方块；0 不额外限制。");
        d.put(p + "rod.max-lifetime-ticks", "鱼钩飞行寿命 tick 上限，0 不额外限制。");
        d.put(p + "rod.impact-strength", "鱼竿命中强度；Classic 以该值除以 0.4 缩放目标向量，0.4 为完整向量。");
        d.put(p + "native.enabled", "Modern 在核对原生公式一致时重新计算推力，否则回退到增量倍率处理。");
        d.put(p + "native.horizontal-retention-ground", "Modern 原生公式分支保留命中前地面水平动量的比例。");
        d.put(p + "native.horizontal-retention-air", "Modern 原生公式分支保留命中前空中水平动量的比例。");
        d.put(p + "native.vertical-retention-ground", "Modern 原生公式分支保留命中前地面 Y 速度的比例。");
        d.put(p + "native.vertical-max-ground", "Modern 原生公式分支的地面结果 Y 速度上限。");
        d.put(p + "native.air-vertical-mode", "Modern 原生空中 Y 处理：preserve 保留，add 使用配置的空中上限。");
        d.put(p + "native.vertical-max-air", "Modern 原生空中 Y 模式为 add 时的 Y 速度上限。");
        d.put(p + "classic.horizontal-ground", "Classic 地面基础目标水平速度，不含疾跑与击退附魔附加；不是格数。");
        d.put(p + "classic.horizontal-air", "Classic 空中基础目标水平速度，不含疾跑与击退附魔附加。");
        d.put(p + "classic.vertical-ground", "Classic 地面基础目标 Y 速度。");
        d.put(p + "classic.vertical-air", "Classic 空中基础目标 Y 速度。");
        d.put(p + "classic.horizontal-retention", "Classic 命中前水平速度保留比例，0 表示覆盖旧水平动量。");
        d.put(p + "classic.vertical-retention", "Classic 命中前 Y 速度保留比例。");
        d.put(p + "classic.sprint-horizontal", "Classic 疾跑命中增加的水平速度。");
        d.put(p + "classic.sprint-vertical", "Classic 有疾跑或击退附魔水平附加时增加的 Y 速度。");
        d.put(p + "classic.enchantment-horizontal", "Classic 每级击退附魔增加的水平速度。");
        d.put(p + "classic.vertical-max", "Classic 输出 Y 速度的上限。");
        d.put(p + "classic.respect-resistance", "Classic 是否应用自定义及其他抗击退属性；下界合金护甲自身抗性始终忽略。");
        d.put(p + "classic.y-limit-enabled", "Classic 近战是否启用离地高度限制。");
        d.put(p + "classic.max-height", "Classic 离地高度超过此值时触发 Y 限制，单位为方块。");
        d.put(p + "classic.vertical-after-limit", "Classic 触发离地高度限制后采用的 Y 速度。");
        DESCRIPTIONS = Collections.unmodifiableMap(d);
    }
}

# ModernKnockBack 2.8.0

Minecraft 1.21.11 / Java 21+。本页只说明 Classic、Modern 配置参数和管理命令。

## 命令

`/modernkb`、`/mkb`、`/kb` 是同一组命令别名；`/kbtest` 用于切换模式或重载。所有命令要求 `modernkb.admin` 权限，默认授予 OP。

| 命令 | 作用 |
|---|---|
| `/mkb help` | 查看中文命令帮助。 |
| `/kb gui` | 打开当前模式的配置菜单；仅玩家可用。菜单可选择默认值或 profile，修改后立即校验、保存并应用。 |
| `/mkb` 或 `/mkb status` | 查看插件、服务器、当前模式、profile 数量、击退处理统计、KnockbackSync 状态，以及 Classic 的受击间隔和保护状态。 |
| `/mkb list` | 列出当前模式可用的 profile。 |
| `/mkb view <profile>` | 查看指定 profile 当前加载的参数。 |
| `/mkb reload` | 校验并重载当前模式的配置；Classic 同时重载 `item.yml` 和所需的 Modern 配置。 |
| `/kbtest classic` | 切换到 Classic 模式。模式会保存并在服务器重启后继续使用。 |
| `/kbtest modern` | 切换到 Modern 模式。模式会保存并在服务器重启后继续使用。 |
| `/kbtest reload` | 重载当前模式的配置。 |
| `/mkb get <玩家名或UUID>` | 查看玩家当前 profile 和是否被排除。玩家名仅能解析在线玩家；离线玩家请用 UUID。 |
| `/mkb setprofile <玩家名或UUID> <profile>` | 为玩家指定 profile。使用 `default` 恢复按击打类型自动选择。 |
| `/mkb exclude <玩家名或UUID> <true/false>` | 设置是否排除此玩家；排除只停止本插件修改，不会取消原版击退。 |

## 配置结构

服务器配置位于 `plugins/ModernKnockBack/`：

| 文件 | 用途 |
|---|---|
| `classic/config.yml`、`modern/config.yml` | 当前模式的总开关、击打分类路由、Bot 识别和 KnockbackSync 策略。 |
| `classic/profiles.yml`、`modern/profiles.yml` | 击退参数。`defaults` 提供继承值；`profiles.<名称>` 只需写出要覆盖的字段。 |
| `classic/combat.yml` | Classic 攻击间隔、疾跑、jump reset、CPS 密度和战斗反馈。 |
| `classic/item.yml` | Classic 模式下使用高版本原生攻击机制的主手物品列表。 |

选 profile 的优先级：精确 `matchups` → 玩家手动分配的非 `default` profile（`respect-player-profile: true` 时）→ 类型路由 → `default`。`profiles.yml` 中未写出的字段继承 `defaults`。profile 自己的 `enabled: false` 只关闭该 profile；`config.yml` 的 `enabled: false` 关闭整套模式配置。

## 两种模式共用的 `config.yml`

| 参数 | 含义 |
|---|---|
| `config-version` | 配置格式版本，当前为 `1`，不要手动修改。 |
| `enabled` | 当前模式的总开关。关闭后不修改 KB。 |
| `affect-other-living-entities` | 是否修改普通生物 KB。`false` 时玩家和已识别 Bot 仍可处理。 |
| `respect-player-profile` | 是否优先采用玩家手动分配的非 `default` profile。精确 `matchups` 仍优先。 |
| `bot-metadata` | Bukkit metadata 名称列表。实体匹配任一名称时识别为 Bot；API 的 Bot 标记可覆盖此判断。 |
| `compatibility.knockbacksync` | `preserve-y` 或 `before-sync`。Modern 默认 `preserve-y`：真实玩家近战的 Y 分量留给 KnockbackSync；`before-sync` 允许先写入完整向量，但后续同步仍可能改写 Y。Classic 默认 `before-sync`，并由 `combat.yml` 的速度保护控制最终速度事件。 |
| `profiles` | 击打类型到 `profiles.yml` profile 名称的映射。默认包括 `melee`、`bot-melee`、`arrow`、`crossbow`、`trident`、`mace`、`spear`、`sweep`、`shield-block`、`fishing-rod`、`wind-charge`、`explosion`、`crystal`、`tnt`、`regularity`、`grim-reaper`、`other-projectile`、`damage`、`unknown`。 |
| `matchups` | 可选的精确覆盖，格式为 `<击打类型>.<攻击者类别>.<受击者类别>: <profile>`。类别是 `player`、`bot`、`other`、`unknown`；例如 `arrow.player.bot: arrow_to_bot`。目标 profile 必须已定义。 |

普通近战中只要攻击者或受击者是 Bot，就走 `bot-melee` 路由。自定义 profile 可在 `profiles.yml` 的 `profiles:` 下新增，再从路由或 `matchups` 指向它。

## Classic 参数

### `classic/profiles.yml`

Classic 参数是目标速度值，不是格数。普通默认值：水平 `0.4`、地面 Y `0.36075`、空中 Y `0.24775`、疾跑水平附加 `0.5`、附加 Y `0.1`。可选的高级字段不一定出现在新配置模板中，但可添加到 `defaults` 或单个 profile。

| 参数 | 含义 |
|---|---|
| `enabled` | 当前 profile 是否参与修改 KB。 |
| `classic.horizontal` | 地面和空中共用的水平基础速度简写；`horizontal-ground`、`horizontal-air` 可分别覆盖。 |
| `classic.horizontal-ground` / `classic.horizontal-air` | 地面／空中的基础水平速度，不含疾跑和击退附魔加成。 |
| `classic.vertical-ground` / `classic.vertical-air` | 地面／空中的基础 Y 速度。 |
| `classic.horizontal-retention` | 受击前水平动量保留比例。`0` 不保留，`0.5` 保留一半。 |
| `classic.vertical-retention` | 受击前垂直动量保留比例。 |
| `classic.sprint-horizontal` | 疾跑近战的额外水平速度。 |
| `classic.sprint-vertical` | 有疾跑或击退附魔带来的水平加成时，额外增加一次 Y 速度。 |
| `classic.enchantment-horizontal` | 击退附魔每级增加的水平速度，默认每级 `0.5`。 |
| `classic.vertical-max` | 最终向上 Y 速度上限。 |
| `classic.respect-resistance` | 是否应用其他抗击退属性。Classic 始终忽略下界合金护甲自带的抗击退；开启时仍保留其他抗性影响。 |
| `classic.y-limit-enabled` | 是否启用近战高度限制，默认关闭。 |
| `classic.max-height` | 超过离地高度阈值后触发 Y 限制，单位为方块。 |
| `classic.vertical-after-limit` | 触发高度限制后使用的 Y 速度。 |

鱼竿使用独立 profile。当前 `fishing_rod` 默认水平 `0.50`、地面和空中 Y `0.34`，且关闭疾跑附加；鱼竿不会套用近战疾跑加成。

### `classic/combat.yml`

tick 按服务器 tick 计；20 TPS 时 10 tick 约为 500 毫秒。这里的间隔是受击保护，不是攻击者的点击冷却。

| 参数 | 含义 |
|---|---|
| `hit-interval-ticks` | 同一受击者普通近战的最小受击间隔，默认 `10`。保护期内的攻击不排队执行。 |
| `rod-hit-interval-ticks` | 鱼竿重复受击间隔；实际间隔取此值与 `hit-interval-ticks` 的较大值。 |
| `rod-melee-interval-ticks` | 鱼竿命中后接刀保护间隔；实际取三个间隔字段中的最大值。 |
| `disable-sweep` | `true` 时关闭横扫攻击。 |
| `legacy-hit-effects` | `true` 时隐藏部分现代攻击提示粒子和提示音，保留受击变红、受伤声及暴击／附魔反馈；不改变伤害和 KB。 |
| `protect-velocity-from-sync` | 是否在速度事件中保护本次 Classic 速度，降低同步插件覆盖该速度的可能。 |
| `stop_sprint` | `false` 时按客户端疾跑状态计算 KB，攻击后的服务器疾跑重置不会消耗下一刀的疾跑加成；玩家主动停止疾跑仍生效。`true` 时按服务器疾跑状态计算。 |
| `jump-reset-enabled` | 是否识别并应用 jump reset 的动量保留。反向动量最多抵消本次水平推力的 75%，保留至少 25% 前向推力。 |
| `jump-reset-window-ticks` | 确认跳跃输入、跳跃事件及成功移动的有效窗口长度，默认 `2` tick。 |
| `click-density-enabled` | 是否按受击者最近 tick 的密集实体攻击尝试减少自身水平退速。2/3/4/5 次约减少 5%/10%/15%/20%，最高 20%；不增加对方 KB 或伤害，也不缩短受击间隔。 |
| `legacy-axe-damage` | 是否在 Classic 中把标准木／金、石、铁、钻石斧基础总伤害适配为 1.8 数值 4/5/6/7。它改变伤害，不是 KB 参数。 |

旧字段 `reset-sprint-on-hit` 仅作兼容：未设置 `stop_sprint` 时可代替它；不要同时设置相反的值。

### `classic/item.yml`

| 参数 | 含义 |
|---|---|
| `modern-items` | 主手物品白名单。命中列表时使用原生高版本攻击冷却和武器机制，并从 Modern 配置读取 KB。默认是七种长矛和 `MACE`；设置为 `[]` 可关闭全部例外。 |

## Modern 参数

### `modern/profiles.yml`

Modern 普通模式调整原生 KB 增量：最终速度由原有速度和击退增量组成。倍率 `1.0` 保持原值；这些倍率不是击退距离。

| 参数 | 含义 |
|---|---|
| `enabled` | 当前 profile 是否参与修改 KB。 |
| `horizontal.ground` / `horizontal.air` | 地面／空中水平击退增量倍率。 |
| `vertical.ground` / `vertical.air` | 地面／空中垂直击退增量倍率。 |
| `modern.sprint-multiplier` | 疾跑近战的额外倍率。 |
| `modern.critical-multiplier` | 暴击近战的额外倍率，不改变暴击伤害。 |
| `modern.weak-hit-multiplier` | 未满蓄力近战倍率：蓄力 0 时使用该值，满蓄力时过渡到 `1.0`。 |
| `limits.horizontal` | 水平击退增量长度上限；`0` 不限。 |
| `limits.upward` | 向上击退增量上限；`0` 不限。 |
| `limits.downward` | 向下击退增量绝对值上限；`0` 不限。 |

疾跑、暴击和蓄力倍率用于近战／横扫。原生攻击冷却、伤害及武器机制仍由服务器处理。

### Modern 原生公式参数

| 参数 | 含义 |
|---|---|
| `native.enabled` | 尝试按已验证的原生攻击公式调整击退。公式或事件数据不匹配时自动回退到普通倍率路径。 |
| `native.horizontal-retention-ground` / `native.horizontal-retention-air` | 地面／空中时受击前水平动量的保留比例；原版公式常用 `0.5`。 |
| `native.vertical-retention-ground` | 地面命中时受击前 Y 速度的保留比例。 |
| `native.vertical-max-ground` | 原生公式下地面击退后的 Y 速度上限。 |
| `native.air-vertical-mode` | `preserve` 保留原生空中 Y；`add` 根据上限添加向上的 Y 速度。 |
| `native.vertical-max-air` | `air-vertical-mode: add` 时使用的空中 Y 速度上限；不会为满足上限而降低更快的上升速度。 |

### 两种模式共用的鱼竿 profile 参数

在 `profiles.yml` 的 `defaults.rod` 设置全局继承值，也可在 `profiles.<名称>.rod` 单独覆盖。

| 参数 | 含义 |
|---|---|
| `rod.enabled` | 是否启用插件鱼竿控制；Classic 下也控制自定义鱼竿 KB。 |
| `rod.cast-interval-ticks` | 两次成功出杆的最短间隔。 |
| `rod.recast-delay-ticks` | 收杆后再次出杆前的最短等待时间。 |
| `rod.horizontal-speed-multiplier` | 鱼钩出杆时水平初速度倍率，`1.0` 为原版。 |
| `rod.vertical-speed-multiplier` | 鱼钩出杆时垂直初速度倍率，`1.0` 为原版。 |
| `rod.gravity-multiplier` | 鱼钩飞行时的额外下落加速度倍率；`1.0` 为原版，调高会更快下落。 |
| `rod.max-distance` | 鱼钩飞行距离上限；`0` 不添加距离限制。 |
| `rod.max-lifetime-ticks` | 鱼钩寿命上限；`0` 不添加寿命限制。 |
| `rod.impact-strength` | 鱼钩碰撞 KB 强度；`0` 关闭碰撞 KB。Classic 中 `0.4` 是完整向量强度；Modern 中作为原生击退强度使用。它不增加鱼竿伤害，也不控制收杆拉回力度。 |

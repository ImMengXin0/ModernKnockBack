# ModernKnockBack 1.21.11 使用与接口

当前版本 2.0.5-r1，撤回 2.0.6，以 2.0.5 为基础恢复此前鱼钩命中后轻微回弹的表现；保留固定受击角度和 Leaf 立即同步时第二次速度事件的保护，见 [视角、鱼钩与同步](CLASSIC-FEEL.md)。每枚鱼钩只触发一次受击，右键收杆不拉回目标；继续屏蔽横扫音效和剑气粒子。普通近战保持原生3.0格距离，保留2.0.1贴身暴击方向修复。classic 鱼钩使用固定0.3格碰撞余量，连续杆及竿接刀默认保护10 tick，保留即时KB、防互推。源码依据见 [鱼竿修复](CLASSIC-ROD-STACK.md)，技巧复测见 [Classic 技巧与延迟](CLASSIC-TECHNIQUES.md)。自动补全见 [配置补全](CONFIG-COMPLETION.md)，双模式及接口见 [战斗模式](COMBAT-MODES.md)。本文现代公式描述 modern 路径。

## 安装

1. 使用 Java 21 或更高版本，服务器版本必须为 Minecraft 1.21.11。
2. 把 `ModernKnockBack-2.0.5-r1.jar` 放入服务器 `plugins`，移除旧版本 ModernKnockBack Jar 后正常重启。本插件不依赖旧 KnockbackManager；检测到旧插件同服存在时会拒绝启用，以免叠加修改。
3. 启动后检查 `/mkb status`，编辑当前模式目录 `plugins/ModernKnockBack/modern/` 或 `classic/` 内的配置，用 `/mkb reload` 重载，用 `/kbtest classic` 或 `/kbtest modern` 切换。
4. 配置错误会拒绝应用，并保留当前已加载配置。启动时配置损坏会明确报错、停止启用，不会生成一份默认配置覆盖损坏文件。

没有修改 ThePit 核心、Leaf/ASPaper/Folia 核心或旧版 KnockbackManager。ASP 在这里指本地 `asp-server.jar` 对应的 ASPaper。

## 现代击退处理

插件只在 Paper `io.papermc.paper.event.entity.EntityKnockbackEvent` 中修改击退，不再监听 Bukkit 旧击退事件，也不会把同一次事件改两遍。爆炸的 Paper 事件如果没有公开直接来源，会安全归类为 `explosion`；不要用旧事件监听器来补改速度。

这里的向量是**击退增量**：最终速度 = 原有速度 + 事件增量。它不是最后的速度、伤害值，也不是实际飞行距离。默认增量模式的水平倍率会同时缩放原生增量中的动量衰减部分。1.1 的 native 模式可在验证普通受击公式后独立控制新增推力与旧速度保留，但仍向事件写回增量；两者都不改变后续移动摩擦。

原版负责攻击冷却、疾跑与附魔击退资格、抗击退属性、爆炸抗击退、盾牌、横扫、重锤及长矛武器机制。默认倍率为 1，不重复计算属性抗性，不改攻击速度、伤害、无敌帧或发包。原生一次攻击可能有多个增量组件，例如伤害击退和附魔额外击退；每个组件各处理一次，不合成额外的一次攻击。

| 配置项 | 含义与默认值 |
| --- | --- |
| `enabled` | 此配置是否修改 KB，默认 true；false 表示保留原生，不是取消原生击退 |
| `horizontal.ground / air` | 受击者在地面/空中时的水平增量倍率，1 |
| `vertical.ground / air` | 地面/空中的垂直增量倍率，1 |
| `modern.sprint-multiplier` | 近战/横扫在捕获到疾跑状态时的额外倍率，1 |
| `modern.critical-multiplier` | 已确认暴击时的额外倍率，1；不更改暴击伤害 |
| `modern.weak-hit-multiplier` | 蓄力为 0 时的额外倍率，1；随蓄力线性过渡到满蓄力的 1 |
| `limits.horizontal` | 水平增量长度上限，0 不限 |
| `limits.upward / downward` | 正/负垂直增量上限，0 不限 |

倍率范围 0～4，增量上限范围 0～10。额外现代倍率在默认模式同时作用水平与垂直增量；native 模式只调整新增推力。攻击前快照捕获 charge/sprint，匹配受击对象和同一实体 tick；无法可靠获得的信息不会凭空补成“弱击”。暴击必须匹配当前伤害事件。默认增量模式的零增量乘倍率仍为零；native 模式允许改变原本互相抵消的推力/动量，air add 可补充原本为零的 Y，但不会在核心未触发击退事件时补打一击。

modern 路径不重写攻击冷却、重锤落高伤害或附魔等级。classic 的无蓄力攻击由独立逻辑提供。特定核心关闭了对应原生事件或其他插件在后续阶段重写速度时，本插件不能保证最终手感。

## 分类与优先级

`config.yml` 的 `profiles` 是击打类型到配置名称的映射，实际参数定义在 `profiles.yml`。每个具名配置继承 `defaults`，只有显式写出的字段覆盖默认值。字段拼写错误、错误类型、非有限值、超范围或不存在的路由目标都会拒绝加载。

内置配置：`player`、`bot`、`arrow`、`crossbow`、`trident`、`mace`、`spear`、`sweep`、`shield`、`fishing_rod`、`wind_charge`、`explosion`、`crystal`、`tnt`、`regularity`、`grim_reaper`、`projectile`、`default`。

优先级：`matchups` 精确匹配 → 受击者指定的非 default 配置（开启 respect-player-profile 时）→ 击打类型路由 → default。Bot 普通近战路由在攻击者或受击者任何一方为 Bot 时使用；箭等其他类型可通过 matchups 区分。

```yaml
# config.yml
matchups:
  arrow.player.bot: arrow_to_bot
  melee.bot.player: bot_to_player
```

```yaml
# profiles.yml 的 profiles 下新增
profiles:
  arrow_to_bot:
    horizontal:
      ground: 0.90
      air: 0.85
  bot_to_player:
    horizontal:
      ground: 0.95
```

对战方类别：`player`、`bot`、`other`、`unknown`。Bot 通过 `NPC / Bot / bot` 元数据识别，也可用 API 的 PDC 标记覆盖。NPC 插件重新创建实体且不保留 PDC 时，需要重新标记。默认只调整玩家和已识别 Bot；其他生物需要 `affect-other-living-entities: true`。

## 鱼竿参数与推荐起点

现代原版鱼竿碰撞是勾住实体，不会像 1.8 一样自动产生碰撞受击和击退。下面是 modern 的可选轻微碰撞击退配置，modern 保持收杆拉回走原版。classic 使用模式目录的经典向量，且从 1.2.4 起命中走真实 0 伤害受击、收杆取消生物拉回：

```yaml
# 合并到 profiles.yml 的 profiles 中，不要重复写第二个 profiles 根节点
profiles:
  fishing_rod:
    horizontal:
      ground: 1.0
      air: 0.90
    vertical:
      ground: 1.0
      air: 1.0
    rod:
      enabled: true
      cast-interval-ticks: 4
      recast-delay-ticks: 2
      horizontal-speed-multiplier: 1.05
      vertical-speed-multiplier: 1.0
      max-distance: 24.0
      max-lifetime-ticks: 100
      impact-strength: 0.20
```

这是供实服调试的温和起点；20 TPS 下 4 tick≈200ms、2 tick≈100ms、100 tick≈5 秒。classic 的独立推荐片段为 presets/classic-rod-responsive.yml，水平 0.50、Y=0.34、impact-strength=0.4，抛投/收杆间隔 2/0；只合并到 classic 的 fishing_rod，保留近战 profile。

1.2.6 起缺失字段自动补全；上述片段仅供主动采用整套推荐数值时使用。已有直接值及 fishing_rod 原来显式继承的 defaults 值优先保留，普通与自定义 profile 继续继承 defaults。classic 鱼竿强度在 profile 与 defaults 都缺失时会补为0.4；已有0（关闭）或其他强度仍保留。

| 鱼竿字段 | 行为 |
| --- | --- |
| `rod.enabled` | 是否启用本插件鱼竿控制；false 不禁用原版钓鱼 |
| `cast-interval-ticks` | 两次成功抛投的最小间隔；0 不额外限制，最大1200 |
| `recast-delay-ticks` | 成功收杆后再次抛投的间隔；0 不限，最大1200 |
| `horizontal-speed-multiplier` | 鱼钩初期飞行 X/Z 速度倍率，默认1，范围0～4 |
| `vertical-speed-multiplier` | 鱼钩初期飞行 Y 速度倍率，默认1，范围0～4 |
| `max-distance` | 距离鱼钩首个调度 tick 位置的最大范围，0 不额外限制，最大32格；不是距移动中的玩家 |
| `max-lifetime-ticks` | 从首个调度 tick 起算的钩子存活上限，0 不限，最大12000 |
| `impact-strength` | 碰撞强度，范围0～2；字段与 defaults 都缺失时回落0关闭。classic 使用此值/0.4 缩放目标向量，0.4 为完整、0.2 为一半；modern 使用原生强度及配置倍率。抗性生效一次 |

“挥杆速度”在服务端可稳定控制的是抛投频率与钩子飞行速度，物品选中与手臂动画仍由客户端处理。1.2.5 在抛投事件中确认新钩子归当前区域拥有后，立即修改初始速度；尚未拥有时回退到钩子的首次实体调度，只缩放一次。距离与寿命监测仍从首个调度 tick 开始，已抛出的钩子沿用抛投时捕获的参数。

classic/combat.yml 三项 hit-interval-ticks、rod-hit-interval-ticks、rod-melee-interval-ticks 默认均为10。连续杆取前两项最大值，接刀取三项最大值；旧4/1/2配置保留，但不能缩短共同受击窗口。默认零伤害杆 noDamageTicks=20，第10 tick降到10；窗口内连续杆/刀不排队、不刷新免疫。受击保护不限制移动。真实伤害、KB或速度取消时撤回自身临时状态；成功杆沿用 2.0.5 的顺序，立即发送一次速度，再补固定方向受击效果。2.0.5-r1 恢复保留鱼钩与钓线并轻微向后、向下回弹，回弹只作用于钩子，不更改玩家 KB；取消或免疫接触也不允许同钩再次受击。classic 飞行鱼钩使用核心线段检测及固定0.3余量；现代路径不改。详见修复文档和验证边界。

modern 的 `impact-strength` 不增加伤害，也不改受伤无敌帧。监听 `ProjectileHitEvent` 且忽略已取消事件，因此 modern 保护插件应在 LOW～HIGH 优先级取消不允许的鱼钩碰撞。classic 则额外调用真实零伤害投射物受击，伤害事件的最终取消状态也会阻止受击反馈和自定义 KB。外层碰撞在 HIGHEST 阶段处理，之后才取消外层碰撞无法回滚已经完成的嵌套伤害流程。

Folia 上钩子、持竿玩家、目标必须由当前区域拥有，否则跳过额外碰撞 KB，不排队补打一击。范围与生命周期监测只操作钩子自己的区域。正常鱼咬钩失败（FAILED_ATTEMPT）不会被误认作收杆。

已检查的 1.21.11 直接在 `FishingHook.pullEntity` 增加速度，不发布 Paper KB 事件。classic 在收杆事件执行该方法之前取消、解钩并移除鱼钩，阻止玩家/生物拉回；modern 沿用原生拉回。无需额外 fishing-pull 参数，也不在下一 tick 覆盖目标速度。

## KnockbackSync、Leaf、ASPaper 与 Folia

`compatibility.knockbacksync` 支持：

- `preserve-y`（默认）：检测到启用的 KnockbackSync，且双方为真实玩家的近战时，保留原生 Y 增量。即使本插件扩展事件修改 Y，也会在提交前恢复；X/Z 仍可调整。
- `before-sync`：允许本插件先调整 Y，但 KnockbackSync 后续可能替换垂直速度，不保证最终等于配置值。

modern 不使用本插件的 `PlayerVelocityEvent` 保护；classic 按 combat.yml 的 protect-velocity-from-sync 在当前速度事件中保留经典最终向量。不拦截速度包、不延迟重写玩家速度，KnockbackSync 继续负责自己的网络与落地同步。其公开 1.3.2 包没有 Folia 支持声明，开发分支已有 Folia 调度适配。**Folia 必须另行安装确实支持 Folia 的 KnockbackSync 构建**；本插件兼容调度不意味着可以让旧版 KnockbackSync 在 Folia 正常加载。`/mkb status` 显示是否加载和共存策略，能发现的无 Folia 声明版本会提示警告；若该插件被服务器拒绝加载，可能显示未安装，需要检查启动日志。

Leaf 启用 `flush-location-while-knockback-player` 时，2.0.5 对原生近战立即刷新 tracker 产生的第二次同步速度事件再保护一次；每次捕获当时实际速度，不在下一 tick 重放，也不改该核心选项。真实网络延迟、客户端插值及反作弊仍需联机检查。

采用 EntityScheduler 执行钩子/玩家任务；磁盘写入走 AsyncScheduler；配置用不可变对象一次性替换；攻击者不属于当前区域时不访问其背包、位置、PDC、元数据等状态，分类退回 unknown。Folia 的跨区射手信息可能未知，因此 arrow.player.bot 等精确路由可能退回 arrow 路由。

核心自身的 KB 选项及其他 KB 插件仍会影响原生增量。建议每种核心先用默认配置对比，再逐项改一个参数。不要使用服务端通用 `/reload` 或插件热卸载作为重启替代。

## 管理与重启

权限：`modernkb.admin`，默认 OP。

```text
/mkb status
/mkb list
/mkb view fishing_rod
/mkb reload
/mkb get 玩家名或UUID
/mkb setprofile 玩家名或UUID player
/mkb exclude 玩家名或UUID true
```

离线目标请用 UUID。`setprofile ... default` 恢复自动分类；`exclude ... false` 重新参与本插件 KB 处理。exclude 不取消原版击退。

配置与玩家设置是两个文件：手动编辑 config/profiles 后 reload；玩家 profile/exclude 用命令或 API 修改。命令在异步调度完成落盘后才回复成功，保存失败不发布内存状态。players.yml 不在 `/mkb reload` 中重新读取，手动维护它应停服后进行。

启动加载的当前模式、/kbtest 目标模式及 /mkb reload 当前模式都会自动补全模式目录内的 config.yml、profiles.yml 和 classic/combat.yml。补全前先验证整套配置，通过后将原文备份到 backups/ 并原子写回；错误语法、类型、路由或数值仍报错，不用默认文件覆盖。补全后再次重载不重复写文件。mode.yml 缺少选择时补为 modern，players.yml 不参与补全。

每次 profile/exclude 更新都使用同目录临时文件、force(true) 和原子替换（文件系统不支持时退回 replace）。启用时重新读取 players.yml，不依赖上次的静态缓存或 onDisable 最后保存。重启测试覆盖保存、重建存储对象、取消排除、恢复 default 与保存失败。操作系统断电等极端持久化保证仍由文件系统决定。

不能删除仍被 players.yml 引用的配置名称：启动/reload 会明确拒绝，而不是让这些玩家静默回落为其他 KB。请先用命令改回 default，再删除配置。

## Java 接口

依赖本插件 Jar，使用 `provided/compileOnly`，不要 shade 本插件 API；在调用方 plugin.yml 加 `softdepend: [ModernKnockBack]` 或硬依赖。通过 ServicesManager 获取：

```java
ModernKnockbackAPI api = Bukkit.getServicesManager().load(ModernKnockbackAPI.class);
if (api != null) {
    // 在实际执行伤害的当前受击者/攻击者共同所属区域调用。
    api.withHitType(attacker, victim, HitType.REGULARITY,
        () -> victim.damage(amount, attacker));
    // 死神：HitType.GRIM_REAPER
}
```

`withHitType` 是同步调用作用域，支持嵌套及异常恢复，不能把伤害放到内部的延迟任务。两实体都必须由当前区域拥有。调用 native `victim.knockback(...)` 也可在作用域内被正确分类。不会在没有原生 KB 的情况下自动额外补击退。现有 ThePit 若尚未迁移至 1.21.11，并不能直接把旧版类加载进本插件；需由高版本技能插件显式调用接口。

```java
api.setBot(entity, true);  // 当前实体所属区域，PDC 标记
api.setBot(entity, false); // 显式判定为非 Bot
api.setBot(entity, null);  // 清除覆盖，恢复元数据判断

// 以下只操作 UUID 和磁盘，可在异步调度调用；方法返回前已保存。
api.setProfile(uuid, "grim_reaper");
api.setExcluded(uuid, false);
String name = api.getProfile(uuid);
```

扩展事件均在受击者当前区域同步发布：

- `KnockbackProfileEvent`：读取 HitContext、改配置名称。取消代表保留该次原生 KB。
- `KnockbackModifyEvent`：读取原生增量和计算后的增量，再 `setImpulse(vector)`；取消代表保留原生 KB。

HitContext 仅含 UUID、类别和数值快照，不含可随意跨线程操作的玩家对象。向量 getter 返回副本，修改副本后需 setter 写回。无效配置名、已禁用配置会保留原生；自定义向量必须为有限数值。扩展事件监听器需要自行遵守 Folia 区域规则。不要在事件里把原有速度再加进 impulse，否则会重复叠加。

自定义事件取消与原生 Paper 事件取消不同：前者不接管该次击退，后者由其他插件决定是否取消整个原生击退。

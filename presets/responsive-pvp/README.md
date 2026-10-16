# 快节奏现代 PvP 试用配置

适用于 ModernKnockBack 2.0.2 的 modern 模式 / Minecraft 1.21.11，与 `../knockbacksync-responsive/config.yml` 配套。全新安装的 modern 配置采用此预设；balanced-modern 是另一套可选起点。

## 数值与目的

| 项目 | 原 balanced-modern | 本预设 |
| --- | --- | --- |
| 玩家、Bot 地面/空中水平倍率 | 0.95 / 0.90 | 1.00 / 0.95 |
| 弓、弩地面/空中水平倍率 | 0.95 / 0.90 | 0.95 / 0.95 |
| 横扫地面/空中水平倍率 | 0.95 / 0.95 | 0.95 / 0.95 |
| 鱼钩地面/空中水平倍率 | 1.00 / 0.90 | 1.00 / 0.95 |
| 抛投间隔 / 收杆后间隔 | 4 / 2 tick | 3 / 1 tick |
| 鱼钩水平飞行倍率 | 1.05 | 1.10 |
| 鱼钩额外寿命上限 | 100 tick | 0（关闭） |

玩家近战地面新增推力恢复原生倍率，减少旧预设对击退表现的压低；空中仅轻微减弱。原生惯性保留比例仍为 0.5，所有垂直倍率为 1.0，空中垂直模式为 preserve，不额外抬升。KnockbackSync 使用 preserve-y 策略处理真实玩家近战垂直同步。

这些倍率作用于匹配原生公式时的新增推力；不匹配或不支持的事件回退到击退增量倍率，不代表位移格数或距离百分比。保留高版本蓄力、疾跑、抗击退和附魔机制，重锤、长矛、三叉戟、风弹及爆炸维持原生倍率。狂暴连击、死神仅保留兼容名称，无专属 KB 调整。

鱼竿在 20 TPS 下两次抛投至少相隔 150ms，收杆后至少等 50ms，两项限制同时生效。提前操作会被拒绝，不会延迟排队；不是客户端挥杆动画速度。碰撞击退强度保留 0.20，不附加伤害；收杆拉回力度仍是原版，Folia 跨区域碰撞可能跳过额外击退。取消额外寿命限制方便普通钓鱼，但仍保留从鱼钩首个调度 tick 位置计算的 24 格距离限制。距离限制会产生逐 tick 追踪；不需要时将 max-distance 也设为 0。纯原版鱼竿玩法将 impact-strength 设为 0.0。

## 使用

1. 使用 ModernKnockBack 2.0.2。
2. 备份服务器 `plugins/ModernKnockBack/modern/` 两份配置和根部的 players.yml。
3. 将本目录 config.yml、profiles.yml 放入 `plugins/ModernKnockBack/modern/`。不要覆盖 players.yml。已有自定义且已分配给玩家的 profile 定义需合并保留，否则 reload 会拒绝加载。
4. 执行 `/kbtest modern`；已在modern时执行 `/mkb reload`。确认成功后用 `/mkb status` 检查启用状态与 KnockbackSync 检测结果。Folia 需使用支持 Folia 的 KnockbackSync 构建。
5. 已手动分配其他 profile 的测试玩家，可用 `/mkb setprofile 玩家名 default` 恢复自动按击打类型选择；被排除的玩家用 `/mkb exclude 玩家名 false` 恢复处理。

KnockbackSync 使用的是另一份配置：将 `../knockbacksync-responsive/config.yml` 放入 `plugins/KnockbackSync/`，不要与 MKB 的 config.yml 混放。KBS 配置按其版本要求加载；需要同时应用时建议正常重启服务器，不使用 Bukkit 全局 reload。

## 验证与微调

用正常 TPS、相同装备及固定场地对比满蓄力地面攻击、疾跑攻击、空中受击、弓箭与鱼竿。用 `/mkb status` 检查 native applied / fallback / formula mismatches；箭矢 PUSH 回退正常，普通近战持续公式不匹配时应检查核心或其他插件对 KB 的修改。正常重启后重复对比并检查启用状态、玩家排除和 profile 分配。

空中击退仍偏弱可将 player、bot 的 horizontal.air 改成 1.0；鱼竿偏强可将 impact-strength 降到 0.15，或将水平飞行倍率回调到 1.05。每次只改一项并 reload 后对比。

这是一套尚未经真人 PvP 实测的起始配置。它调整击退表现和鱼竿节奏，不能降低网络 RTT，也不能修复由低 TPS、反作弊或伤害判定导致的延迟。

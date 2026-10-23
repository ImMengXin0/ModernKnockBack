# 温和现代 PvP 配置

适用于 ModernKnockBack 2.0.6 的 modern 模式 / Minecraft 1.21.11。这是一套温和的可选起点；全新安装的 modern 默认使用 responsive-pvp 预设。

备份服务器 `plugins/ModernKnockBack/modern/` 中的 config.yml 和 profiles.yml，将本目录同名的两份文件合并放进去，然后执行 `/kbtest modern`；已在modern时执行 `/mkb reload`。看到应用成功后，用 `/mkb status` 查看状态。

| 类型 | 地面水平倍率 | 空中水平倍率 | 垂直倍率 |
| --- | --- | --- | --- |
| 玩家、Bot 近战 | 0.95 | 0.90 | 1.00 |
| 弓箭、弩箭 | 0.95 | 0.90 | 1.00 |
| 横扫 | 0.95 | 0.95 | 1.00 |
| 鱼钩碰撞 | 1.00 | 0.90 | 1.00 |
| 三叉戟、重锤、长矛、盾牌、风弹及爆炸 | 1.00 | 1.00 | 1.00 |

玩家、Bot、弓弩普通受伤、横扫及鱼竿配置启用 native 模式：匹配普通受击公式时，倍率作用于新增推力，旧水平速度保留比例固定为 0.5。爆炸、箭矢冲击附加 PUSH、数据不匹配等事件仍用原生增量倍率。不能直接理解为击退距离缩短相同比例。原版蓄力、疾跑、附魔及抗击退继续生效。狂暴连击、死神仅保留兼容名称，不启用 native，不做专属调参。

`/mkb status` 显示 native 成功、回退和公式不匹配次数。箭矢附加 PUSH 回退属正常；若普通玩家近战也持续出现 formula mismatches，检查核心特殊 KB 配置及其他击退插件。参数和限制详见 [现代原生公式](../../docs/NATIVE-KB.md)。

鱼竿采用抛投间隔 4 tick、收杆后间隔 2 tick、水平飞行速度 1.05 倍、范围 24 格、存活时间 100 tick、碰撞击退强度 0.20。范围从钩子首个调度 tick 的位置起算。此预设面向鱼竿 PvP：100 tick 的寿命会影响普通钓鱼，兼顾钓鱼时将 `max-lifetime-ticks` 改为 `0`；不想增加碰撞击退时将 `impact-strength` 改为 `0.0`。

KnockbackSync 策略为 `preserve-y`。Folia 仍需要支持 Folia 的 KnockbackSync 构建。

已有 `/mkb setprofile` 指定会继续优先。例如指定 `player` 的受击者，弓箭击退也会使用 player 配置。需要自动分类时执行 `/mkb setprofile 玩家名 default`；被排除的玩家需执行 `/mkb exclude 玩家名 false`。若原配置另有自定义 profile 且已分配给玩家，应将对应定义一并保留，否则 reload 会拒绝加载。

建议先在正常 TPS、固定装备、同一场地测试普通攻击、满蓄力疾跑、空中连击和鱼竿；这套数值尚未经真人对战实测。

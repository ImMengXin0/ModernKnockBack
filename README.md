# ModernKnockBack 2.0.5-r2

[下载 Release](https://github.com/ImMengXin0/ModernKnockBack/releases/latest) · [反馈问题](https://github.com/ImMengXin0/ModernKnockBack/issues)

Minecraft **1.21.11 / Java 21+** 的独立击退配置插件。沿用 KnockbackManager 的分类配置思路，针对现代 Paper 事件与 Folia 调度重新实现，不依赖旧插件或 ThePit 核心。

- `/kbtest classic|modern` 切换经典无蓄力攻击/旧版向量与现代战斗，保存模式并在重启后继续使用。
- `classic/` 和 `modern/` 独立配置目录，升级自动迁移已有现代配置。
- 启动、切换模式及重载时自动补全当前模式缺失配置；保留已配置数值、旧字段与自定义 profile，修改前自动备份。
- classic 默认只列出 5 个主要 KB 参数；相同命中状态覆盖旧动量，疾跑推力统一方向，关闭默认高度锁定。
- classic 默认 `stop_sprint: false`，按旧 KnockbackManager 的客户端疾跑状态计算 KB；攻击重置不会消耗下一刀的疾跑附加，真实停止/重新开启疾跑及 jump reset 仍生效。
- 玩家、Bot、弓箭、弩、三叉戟、重锤、长矛、横扫、盾牌、风弹、水晶、TNT、一般爆炸独立路由。
- 狂暴连击、死神提供显式 Java API，不靠猜测伤害名称。
- Modern 路径的地面/空中水平与垂直倍率、疾跑/暴击/未蓄满额外倍率、增量限幅。
- Modern 可选原生公式：独立控制新增推力、旧速度保留、地面 Y 上限和空中抬升策略。
- 鱼竿抛投间隔、收杆间隔、飞行速度、距离、存活时间，以及可选碰撞击退。
- classic 鱼钩命中发起真实 0 伤害投射物受击，支持受击动画、声音及经典向量；收杆取消玩家/生物拉回。
- classic 鱼钩接触生物后保留鱼钩和钓线，恢复此前轻微向后、向下回弹的表现；右键正常收回，不拉回目标，同一枚鱼钩只接触一次。
- classic 本人受击改用旧版固定方向晃动，保留受伤动画；不改玩家朝向。沿用 2.0.5 的反馈顺序，先发送鱼竿速度，再播放受击效果。
- classic 连续鱼竿及竿接刀遵守完整受击窗口，默认 10 tick；固定 0.3 格鱼钩碰撞余量，改善近距离横移目标漏判。
- classic 保留原生命中目标和攻击距离，不再根据挥手包补刀或扩大命中边缘；使用玩家 UUID 排除实体互推，保留鱼钩/箭的碰撞资格，modern 恢复原规则。
- classic 屏蔽横扫攻击音效与剑气粒子，支持普通包及组合包；modern 和排除玩家恢复原效果。
- KnockbackSync 共存策略、Folia 实体调度、配置严格校验与原子保存。

文档：[中文配置与接口说明](docs/GUIDE-zh_CN.md)、[双模式与攻击节奏](docs/COMBAT-MODES.md)、[现代原生公式](docs/NATIVE-KB.md)、[配置自动补全](docs/CONFIG-COMPLETION.md)、[鱼竿接刀防叠击](docs/CLASSIC-ROD-STACK.md)、[验证记录](docs/VERIFICATION.md)。狂暴连击、死神不做专属调参。

构建：`mvn package`（包含测试）。产物：`target/ModernKnockBack-2.0.5-r2.jar`。

2.0.5-r2 修复 classic 鱼竿在 API 取消自定义修改、API 选到不存在的 profile 或 profile 禁用时的接受判定：这些情况按接口约定保留原生 KB，旧实现却把已发生的击退判为失败，撤回受击保护并漏掉即时速度和受击反馈。现在按最终原生 KB 事件是否接受来完成命中，保留该次原生向量，并发送一次速度、一次红闪和受击声音；连续杆与接刀继续共用默认 10 tick 窗口。真正取消碰撞、伤害、原生 KB 或速度事件仍会阻止对应处理。保留 2.0.5-r1 的鱼钩微小回弹及速度后反馈顺序，零扣血、同 tick 完成，不增加延迟或配置项。

本次重新核查了 1.2.10：2.0 发布时曾验证 40 个战斗、鱼竿与兼容类的 SHA256 与 1.2.10 一致，当前继续保留其零伤害受击、固定 0.3 碰撞余量和共同受击窗口。该依据确认历史逻辑，不代表已完成当前版本的真人视效验证；详见 [鱼竿修复](docs/CLASSIC-ROD-STACK.md) 和 [验证记录](docs/VERIFICATION.md)。

2.0.5-r1 撤回 2.0.6，以 2.0.5 为基础恢复 2.0.4 的鱼钩轻微回弹表现。回弹只作用于鱼钩，玩家 KB 数值和受击间隔保持原配置；保留 2.0.5 的固定受击角度、Leaf 同步保护和生命周期清理。受击效果回到速度发送之后播放。替换 Jar 并正常重启即可，无需重置配置。详见 [视角、鱼钩与同步说明](docs/CLASSIC-FEEL.md)。

历史版本 2.0.5 将本人受击包角度归零，成功鱼钩改为原生挂载并对受击者隐藏；其中鱼钩挂载/隐藏已在 2.0.5-r1 撤回。该版修复 Leaf 开启 `flush-location-while-knockback-player` 时，同一击第二次速度事件可再次被同步插件改写的问题，限定保护原生同步调用中的那一次事件。Leaf 修复继续保留，不重放速度、不修改核心；实际客户端手感与网络延迟仍须联机复测。

2.0.4 优化 classic 技巧与同步一致性：jump reset 需同时具有跳跃输入、跳跃事件和成功移动，避免把普通击飞认作跳跃；修复 KnockbackSync 直接改实体速度时的保护遗漏，发包时保留实际运动状态，并拦截已取消或过期的击退记录。W-tap/S-tap 按收到的疾跑动作生效，strafe 保持方向对称；不按 ping 加力或延长受击窗口。数值和配置项保持不变，说明及实服复测表见 [Classic 技巧与延迟](docs/CLASSIC-TECHNIQUES.md)。

2.0.3 在 classic 中保留命中后的鱼钩与钓线，命中后轻微回弹下落，收杆间隔从实际收杆开始计算；每枚鱼钩只触发一次受击。同时屏蔽横扫剑气粒子，沿用2.0.2的横扫消音。普通近战仍使用原生3.0格距离，无新增配置项。无需删除已有配置；验证范围见 [验证记录](docs/VERIFICATION.md)。

2.0.1 修复 classic 贴身跳跃暴击时偶发的方向偏转：双方水平位置几乎重合时，使用命中时攻击者的水平朝向，避免放大极小位置差或沿用核心的随机方向。正常距离仍按攻击者到受击者的方向推开。

2.0 为公开发布版本，沿用已确认可正常击退的 classic/modern 实现。安装时只保留一个本插件 Jar，并正常重启。Linux 等区分大小写的文件系统会在新目录尚无数据时将旧 `plugins/ModernKnockback/` 迁移到 `plugins/ModernKnockBack/`；已有配置、玩家分配与模式一并保留。若两个目录都已有数据，使用新目录且不合并覆盖。Java API 类名保持兼容，调用方依赖名称使用 `ModernKnockBack`。

默认启用 modern。已有 1.0/1.1 配置自动迁移保留，全新 modern 使用 responsive-pvp 预设，classic 使用旧天坑 KB 起点。修改当前模式目录内的配置后用 `/mkb reload`。不要与旧版 KnockbackManager 同服加载。

2.0 修复近距离鱼钩仍使用现代动态余量、零伤害杆提前结束免疫的问题。classic 使用旧版固定 0.3 碰撞余量，连续杆及竿接刀至少遵守 hit-interval-ticks（默认10 tick）。旧的短 rod 配置不覆盖原文件，但实际不再缩短共同保护。保留1.2.9的投射物碰撞资格与原生近战距离修复。详见鱼竿修复说明及联网源码依据。

当前模式缺失字段自动写回；切换模式或 `/mkb reload` 也会补全。旧 classic 的 fishing_rod 自动补齐独立向量与 rod 字段，已直接设置或显式继承 defaults 的数值保留，缺失的 impact-strength 补为0.4。备份在插件数据目录 backups/，没有缺项时不重写或重复备份。

全新安装默认杆水平0.50、地面/空中 Y=0.34、强度0.4、抛投间隔2、收杆后间隔0、水平飞行倍率1.15；属于调试起点，未进行真人手感测试。旧配置已有值继续使用，若主动采用整套起点，可使用 [鱼竿配置片段](presets/classic-rod-responsive.yml)。已有玩家 profile 分配优先于类型路由，详见修复说明。

关闭疾跑重置时，将 classic/combat.yml 的 `reset-sprint-on-hit: true` 替换为 `stop_sprint: false`。不要同时保留相互矛盾的两个值。旧版显式启用的高度锁定或动量保留仍生效，详见审查说明。

攻击目标与范围由原生流程处理，已移除1.2.8的命中边缘补判。防互推的服务端部分只添加玩家碰撞排除名单，客户端沿用队伍包副本，不修改真实计分板或名称格式。三核心检查直接调用实际鱼钩候选目标与推挤判定，复现旧问题并验证新处理；尚未完成真人 PvP 或真实 KnockbackSync 网络共存测试。

## 项目目录

```text
src/main/java/        插件源码与公开接口
src/main/resources/   plugin.yml、配置模板及旧配置兼容资源
src/test/java/        自动测试
presets/              可选 MKB 与 KnockbackSync 参数预设
docs/                 当前使用、接口与验证说明
tools/                三核心编译与底层包适配检查（不启动服务器）
dist/                 当前 Jar、干净源码包与验证日志（不纳入源码包）
pom.xml               Java 21 / Maven 构建配置
```

配置模板统一维护在 src/main/resources/classic/ 和 src/main/resources/modern/。服务器启动后自动生成 plugins/ModernKnockBack/ 内的模式配置。`mvn package` 会重新生成 target/。核心检查前运行 `mvn dependency:build-classpath -DincludeScope=test -Dmdep.outputFile=target/test-dependencies.txt`，再在 PowerShell 执行 `./tools/verify-server-apis.ps1 -CoreDirectory '核心 Jar 所在目录'`。可传 `-VanillaBundle 'mojang_1.21.11.jar路径'` 使用本地缓存；检查不会启动游戏服务器。

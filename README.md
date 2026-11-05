# ModernKnockBack 2.6.0

[下载 Release](https://github.com/ImMengXin0/ModernKnockBack/releases/latest) · [反馈问题](https://github.com/ImMengXin0/ModernKnockBack/issues)

Minecraft **1.21.11 / Java 21+** 的独立击退配置插件。沿用 KnockbackManager 的分类配置思路，针对现代 Paper 事件与 Folia 调度重新实现，不依赖旧插件或 ThePit 核心。

- `/kbtest classic|modern` 切换经典无蓄力攻击/旧版向量与现代战斗，保存模式并在重启后继续使用。
- `classic/` 和 `modern/` 独立配置目录，升级自动迁移已有现代配置。
- Classic 的 `classic/item.yml` 支持指定主手物品使用高版本原生攻击与 Modern KB，默认全部 7 种长矛及重锤，支持 reload，见 [物品例外](docs/ITEM-COMBAT.md)。
- 启动、切换模式及重载时自动补全当前模式缺失配置；保留已配置数值、旧字段与自定义 profile，修改前自动备份。
- classic 的 `classic/combat.yml` 新增 `legacy-axe-damage: true`（升级自动补全）：木/金、石、铁、钻石斧按 1.8 基础总伤害 4、5、6、7 计算，暴击、力量、附魔及护甲仍由原版计算。切换武器、modern 模式、排除玩家及停用时恢复；铜/下界合金斧与显式自定义攻击属性的物品保留原值。
- classic 默认只列出 5 个主要 KB 参数；相同命中状态覆盖旧动量，疾跑推力统一方向，关闭默认高度锁定。
- classic 向量忽略下界合金护甲自带的抗击退，保留基础属性和其他自定义抗性，不新增配置；Modern 及 `classic/item.yml` 指定的现代武器继续使用原生抗性。
- classic 默认 `stop_sprint: false`，按旧 KnockbackManager 的客户端疾跑状态计算 KB；攻击重置不会消耗下一刀的疾跑附加，真实停止/重新开启疾跑及 jump reset 仍生效。
- classic jump reset 的反向动量最多抵消本次实际水平推力的 75%，避免将击退完全抵消；侧向走位与正常跳跃收益保留，无需新增配置。
- classic 支持 CPS 密度：按最近两个 tick 中较密集的单 tick 实体攻击次数，在新近战受击时最多额外减少 20% 的水平退速，与 jump reset 共用 25% 推力下限。`click-density-enabled` 默认开启，升级自动补全，见 [密度规则](docs/CLICK-DENSITY.md)。
- 玩家、Bot、弓箭、弩、三叉戟、重锤、长矛、横扫、盾牌、风弹、水晶、TNT、一般爆炸独立路由。
- 狂暴连击、死神提供显式 Java API，不靠猜测伤害名称。
- Modern 路径的地面/空中水平与垂直倍率、疾跑/暴击/未蓄满额外倍率、增量限幅。
- Modern 可选原生公式：独立控制新增推力、旧速度保留、地面 Y 上限和空中抬升策略。
- 鱼竿抛投间隔、收杆间隔、初始飞行速度、空中重力倍率、距离、存活时间，以及可选碰撞击退。
- classic 鱼钩命中发起真实 0 伤害投射物受击，支持受击动画、声音及经典向量；收杆取消玩家/生物拉回。
- classic 成功命中后鱼钩挂住目标，仅对被钩玩家隐藏，出杆者与旁观者保留鱼钩和钓线；取消或免疫命中不挂钩。右键正常收回，不拉回目标，同一枚鱼钩只接触一次。
- classic 本人受击改用旧版固定方向晃动，保留受伤动画；不改玩家朝向。先发送鱼竿速度，再隐藏/挂钩和播放受击效果。
- classic 连续鱼竿及竿接刀遵守完整受击窗口，默认 10 tick；固定 0.3 格鱼钩碰撞余量，改善近距离横移目标漏判。
- classic 保留原生命中目标和攻击距离，不再根据挥手包补刀或扩大命中边缘；使用玩家 UUID 排除实体互推，保留鱼钩/箭的碰撞资格，modern 恢复原规则。
- classic 屏蔽横扫攻击音效与剑气粒子，支持普通包及组合包；modern 和排除玩家恢复原效果。
- classic 默认开启旧版命中反馈：隐藏现代伤害指示粒子与攻击附加音效，保留受击变红、受伤声、挥手和暴击/附魔粒子。用 `classic/combat.yml` 的 `legacy-hit-effects` 开关对比，见 [观感说明](docs/CLASSIC-FEEL.md)。
- KnockbackSync 共存策略、Folia 实体调度、配置严格校验与原子保存。
- 无需配置的网络发送优化：classic / modern 及时刷新本人速度及爆炸包，减少批量发送等待，保留顺序和单次 KB；TCP 负责丢包重传，不按 Ping 改向量，见 [网络补丁](docs/NETWORK-DELIVERY.md)。

文档：[中文配置与接口说明](docs/GUIDE-zh_CN.md)、[双模式与攻击节奏](docs/COMBAT-MODES.md)、[现代原生公式](docs/NATIVE-KB.md)、[配置自动补全](docs/CONFIG-COMPLETION.md)、[鱼竿接刀防叠击](docs/CLASSIC-ROD-STACK.md)、[验证记录](docs/VERIFICATION.md)。狂暴连击、死神不做专属调参。

构建：`mvn package`（包含测试）。产物：`target/ModernKnockBack-2.6.0.jar`。

2.6.0 让 Classic 向量忽略下界合金护甲自带的抗击退，普通攻击、疾跑、鱼竿和弓箭均适用；保留基础与自定义抗性，Modern 及现代武器例外沿用原生规则，无需新增配置。见 [2.6.0 发布说明](docs/RELEASE-2.6.0.md)。

2.5.0 合并 [DevXingMC 的 PR #2](https://github.com/ImMengXin0/ModernKnockBack/pull/2)：修复长矛突刺/突进沿用 Classic 无冷却攻速，补齐切槽、副手交换、装备更新和插件替换手持物品时的即时同步。整合后的例外统一由 `classic/item.yml` 决定，重锤与自定义物品同样生效；补上排除/Modern 状态的旧攻速清理及 Folia 区域检查。包含下述物品例外功能、既有免疫窗口衔接和原有网络补丁，见 [2.5.0 发布说明](docs/RELEASE-2.5.0.md)。

2.4.1 新增 Classic 物品例外：白名单主手武器恢复原生冷却、疾跑资格和武器机制；击退从 `modern/` 对应路由读取，跳过 Classic 向量、CPS 减退及重复组件合并。`/mkb reload` 同时校验列表与依赖的 Modern 配置；鱼竿接武器保护和 Classic 全局碰撞/视听规则继续生效。

2.4.0 将 Leaf 速度派发识别和受击包改写的首次初始化提前到插件启动，减少重启后全服第一击的服务端初始化开销。预初始化只处理内存对象，不发送受击或速度包；详见 [首次击退审查](docs/FIRST-HIT.md)。

2.4.0 合并 [DevXingMC 的 PR #1](https://github.com/ImMengXin0/ModernKnockBack/pull/1)：启动预热战斗类和纯计算路径，将首击提交/清理回调改为显式类，并跳过非旧版斧头的 ItemMeta 读取。保留上述初始化修复及网络发送补丁，不新增配置。整合代码已通过 331 项测试及三核心离线检查；作者的 Paper 冷启动测量见 [PR 验证记录](docs/FIRST-HIT-WARMUP.md)，不是本次整合包的实服性能结果。

2.3.0 新增 classic 的 1.8 斧头基础伤害适配。`legacy-axe-damage: true` 默认开启并自动补全；标准玩家基础属性下，木/金、石、铁、钻石斧的基础总伤害为 4、5、6、7 点。通过临时属性修正交给原生流程计算后续伤害，支持切换武器及切回 modern 时恢复。完整变更和升级方法见 [2.3.0 发布说明](docs/RELEASE-2.3.0.md)。

2.2.0 基于原始 2.0.5，整合 jump reset 反向动量限制、CPS 密度、鱼钩重力倍率和旧版命中反馈。`legacy-hit-effects` 默认开启并自动补全，减少现代额外粒子和音效，保留受击变红、受伤声、挥手及暴击/附魔粒子。它不替换客户端模型或第一人称持刀动画，完整旧版客户端动画仍需客户端支持。完整变更和升级方法见 [2.2.0 发布说明](docs/RELEASE-2.2.0.md)。

`rod.gravity-multiplier` 控制未命中鱼钩的空中下落加速度，范围 1.0～4.0，默认 1.0 保留原版轨迹。想加快下落时在实际使用 profile 的 rod 段设置 1.5；classic 常用 `profiles.fishing_rod.rod.gravity-multiplier`。配置自动补全，classic / modern 分别读取自己的文件，重载后新抛出的鱼钩生效。

密度减退只在新命中计算一次，保留受击间隔、速度同步与模式隔离，不按点击次数连续乘原生攻击减速。

2.0.5 修复 classic 受击的定向镜头倾斜和鱼钩回弹观感：仅把本人受击包角度归零；成功鱼钩改为原生挂载并对受击者隐藏。修复 Leaf 开启 `flush-location-while-knockback-player` 时，同一击第二次速度事件可再次被同步插件改写的问题，限定保护原生同步调用中的那一次事件。保留原 KB 数值、受击间隔及技巧，不重放速度、不修改核心。升级正常重启即可；实际客户端手感与网络延迟仍须联机复测，详见 [视角、鱼钩与同步说明](docs/CLASSIC-FEEL.md)。

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

# Classic / Modern 模式调试

适用于 Minecraft 1.21.11 / Java 21+。管理员执行 `/kbtest classic` 切换到无蓄力攻击与经典击退，执行 `/kbtest modern` 恢复现代战斗。权限 `modernkb.admin`，默认 OP，控制台也可执行。`/kbtest reload` 或 `/mkb reload` 重载当前模式，`/mkb status` 显示模式、配置路径、classic 向量次数和拦截计数。

## 目录与升级

```text
plugins/ModernKnockBack/
  mode.yml                 # 当前模式；成功切换后保存，重启继续使用
  players.yml              # 原有玩家 profile/exclude 数据
  classic/
    config.yml             # 玩家、Bot、弓箭、鱼竿与对战路由
    profiles.yml           # 基础速度、动量、疾跑、附魔、鱼竿
    combat.yml             # 受击间隔、鱼竿间隔、横扫、疾跑重置与跳跃重置
  modern/
    config.yml             # 高版本路由及 KnockbackSync 策略
    profiles.yml           # 高版本增量/native 向量及鱼竿参数
```

首次升级将数据目录根部的 config.yml、profiles.yml 复制到缺失的 modern 同名文件，保留已有调参；原文件保留，此后实际读取模式目录。classic 缺失文件自动生成，已存在的文件不覆盖。全新安装默认 modern，采用 responsive-pvp 起点。源码中的配置模板统一位于 src/main/resources/classic/ 和 src/main/resources/modern/，服务器实际使用 plugins/ModernKnockBack/ 内的模式目录。

启动读取当前模式、切换读取目标模式以及重载当前模式时，自动补齐 config.yml、profiles.yml、classic/combat.yml 中缺失的内置项。已有值、个人分配和自定义 profile 保留；普通 profile 继续继承 defaults，classic 的 fishing_rod 补出独立段并保留原有显式继承值。校验整套候选配置通过后再备份、写回，原文备份在 backups/。无缺项时不写文件；坏配置仍拒绝。详见 [自动补全规则](CONFIG-COMPLETION.md)。

切换前校验目标目录、路由及已分配的玩家 profile；校验或模式保存失败不切换。自定义且已分配的 profile 需要在两套 profiles.yml 中都存在。players.yml 共用，升级无需清空。配置损坏不会自动覆盖为默认值。

模式切换和 reload 清理命中记录、速度保护及鱼竿定时器，并移除本插件已追踪的旧鱼钩，下一次抛竿采用新参数。切换由命令完成，不使用 Bukkit 全局 reload 或第三方热卸载器。

## Classic 攻击与防叠刀

玩家使用本插件命名空间下的临时 ATTACK_SPEED +1024 属性，令高版本原生伤害及疾跑资格不再等待蓄力。不会覆盖基础值或装备 modifier，换武器也无需重写基础属性。核心按半 tick 采样攻击蓄力，该速度使采样满蓄力；无需取消正常伤害再 damage() 补造一次攻击。

受击间隔与蓄力独立。classic/combat.yml 中 hit-interval-ticks 默认 10，即 20 TPS 下 500ms，接近 1.8 常规伤害免疫窗口；maximumNoDamageTicks 对应 20。间隔设为 8 时对应 16。玩家仍可连续挥刀，但窗口内点击不再伤害同一目标，也不会排队延后执行。

classic 默认 `stop_sprint: false`，与旧 KnockbackManager 的同名字段一致：KB 使用客户端 START/STOP 疾跑事件记录，不因核心每次攻击后清除实体 sprint 而失去下一刀的疾跑附加。真实 STOP 会关闭附加，之后真实 START 会恢复；W-tap/S-tap 仍通过客户端发来的状态切换生效，持续疾跑也可连续获得附加。取消的切换不更新记录，插件不会从移动方向猜按键，也不会强制 `setSprinting(true)` 覆盖玩家操作。

`stop_sprint: true` 则使用服务器实体疾跑状态及当前攻击前快照，接受核心原生攻击重置。已删除 1.2.2 命中后额外的延迟清 sprint 任务，避免下一 tick 覆盖新开启的疾跑或跨模式执行。此选项控制 KB 疾跑资格，不改核心的攻击移动减速、原生 sprint 标志或武器伤害。modern 始终读取服务器状态；Player 类型的 Bot 使用自身实体状态。客户端记录在模式切换/reload 时保留，退出、重生及插件关闭时清理。

需要关闭疾跑重置时，将服务器 classic/combat.yml 中的 `reset-sprint-on-hit: true` 替换为 `stop_sprint: false`，再执行 `/mkb reload`。也可将旧字段改为 false。旧字段兼容为同义别名，两个值相互矛盾时校验失败，不改变当前模式；已有值不会自动覆盖。

`jump-reset-enabled: true` 时，2.0.4 起同时核对跳跃输入、Paper 跳跃事件和对应的成功移动。单纯离地或正向 Y 速度不能建立 jump reset；被取消或重定向的移动、飞行/滑翔/游泳/载具和 Bot 不使用该技巧。输入与移动在同 tick 内先后到达均可识别，后续松开跳跃键不取消已成功的起跳；下一 tick 新按的跳跃不能追溯改变之前的击飞。命中发生在 `jump-reset-window-ticks`（默认 2 tick，含边界）内且玩家仍有向上速度时，classic 保留命中前水平与垂直速度的至少一半，再叠加经典推力。它不改变主要 KB 参数。客户端未提供跳跃输入时采用普通空中向量；该规则是技巧模拟，不是完整 1.8 物理还原。

同一受击者共享窗口，防止更高伤害绕过原生免疫造成叠刀。攻击前先拦截，伤害事件兜底；不会逐击清空 noDamageTicks 或延迟重放速度。默认取消横扫伤害和横扫 KB，让刀剑攻击只处理目标。

classic 接收端屏蔽 `minecraft:entity.player.attack.sweep` 横扫音效，2.0.3 同时屏蔽 `SWEEP_ATTACK` 横扫剑气粒子。核心在伤害事件之前就播放横扫声，所以需要在现有客户端规则中过滤音效/粒子包及组合包，不能只取消横扫伤害。受伤、暴击和鱼竿声音以及暴击/伤害粒子正常；切回 modern、排除玩家或卸载时恢复。此规则随 classic 生效，无新增配置项；即使显式关闭 disable-sweep，classic 仍然屏蔽横扫声音和剑气。普通挥手动画保留。

2.0.5 将本人受击包的方向改为旧版固定角度，保留受伤动画和固定倾斜，不修改玩家实际 yaw/pitch 或位置。其他实体的包不改，modern、排除玩家及移除处理器后恢复原样；详见 [视角、鱼钩与同步](CLASSIC-FEEL.md)。

经典向量参考旧 KnockbackManager / ThePit，并在 1.2.1 中统一命中方向，消除疾跑力度随视角变化的问题。2.0.1 在命中时固定近战水平方向：双方水平距离平方小于 `1e-5`（距离约 0.0032 格）时，采用攻击者的水平朝向，避免贴身跳跃暴击把细小位置差放大或使用核心随机方向。该判断不受高度差与俯仰角影响，正常距离仍按双方相对位置推开；鱼竿/箭保留飞行方向，jump reset 与显式动量保留仍会对最终速度产生预期影响。

近战计算如下（几乎重合时用上述朝向代替 normalize）：

```text
finalXZ = beforeXZ * horizontal-retention
          + normalize(attacker -> victim) * (baseHorizontal + sprintBonus + enchantmentLevel * enchantmentBonus)
finalY  = min(vertical-max, beforeY * vertical-retention + baseVertical + bonusVertical)
Paper delta = finalVelocity - beforeVelocity
```

| 参数 | Classic 默认 |
| --- | --- |
| 地面 / 空中水平速度 | 0.4 / 0.4 |
| 地面 / 空中垂直速度 | 0.36075 / 0.24775 |
| 疾跑附加水平 / 垂直 | 0.5 / 0.1 |
| 击退附魔每级附加水平 | 0.5 |
| 水平 / 垂直动量保留 | 0 / 0 |
| 最终垂直上限 | 0.5 |
| 高度锁定 | 默认关闭，空中 Y 不依赖上一次连击高度 |
| 疾跑重置 | `combat.yml` 默认 `stop_sprint: false`；客户端仍处于疾跑时保留下一刀附加 |
| jump reset | `combat.yml` 默认开启；输入及移动确认的最近 2 tick 起跳、有向上速度时保留至少半动量 |

旧天坑代码直接覆盖旧速度，默认动量保留 0。1.2.1 默认配置将主要 KB 参数从 14 项缩为 5 项：classic.horizontal、vertical-ground、vertical-air、sprint-horizontal、sprint-vertical。horizontal 同时指定地面和空中水平；每个 profile 可独立覆盖。classic 计算不再套用通用 horizontal/vertical、modern 倍率或 limits 增量限幅；这些字段继续供现代/未使用 classic 公式的事件路径使用。抗击退默认保留，自算推力只缩放一次。

旧的 horizontal-ground/air、retention、附魔、上限及高度锁定字段仍可加载，以兼容自定义配置；不是默认调参项。显式非零 retention 会继承旧动量，显式 y-limit-enabled: true 会继续改变高处空中 Y。采用精简默认配置时，先备份服务器 classic/profiles.yml，再参考 src/main/resources/classic/profiles.yml 合并需要保留的自定义 profile；要使用默认一致性规则，应移除旧 retention、高度锁定字段，或将它们明确设为0/false，再执行 `/mkb reload`。

高版本一次攻击可能先生成伤害 KB，再生成疾跑/附魔 KB。classic 将附加值合进第一条完整向量，取消后续重复组件。仅最终未取消的 KB 事件提交去重标记；被其他插件取消的组件不会消耗向量。被取消伤害、过期记录、模式变化不会重放速度。现代路径仍处理每一个现代原生增量组件。

玩家和 Bot 独立路由，默认使用相同数值；Bot 在受击时设置对应免疫时间，不依赖 PlayerVelocityEvent 来生成 KB。classic 重锤和长矛的近战 KB 使用经典向量，但武器伤害、特殊使用方式、盾牌、装备等仍由高版本服务器负责。爆炸、风弹、三叉戟特殊机制沿用事件增量路径。这是经典攻击节奏和 KB 模拟，不能将整个 1.21.11 客户端及战斗系统完全还原成 1.8。

切回 modern、排除玩家或玩家退出时，只移除本插件攻速 modifier，并恢复进入 classic 前保存的 maximumNoDamageTicks。若别的插件后来修改了该值，则不覆盖。原始免疫值用 PDC 保存，攻速 modifier 不写入实体存档，重启及重新加入可恢复。Folia 的属性操作在实体所属区域执行；禁用阶段无法直接恢复其他区域实体，正常重启后的实体加载/玩家加入会回收状态。

## Classic 鱼竿

fishing_rod 的推荐默认为抛投间隔 2 tick、收杆后间隔 0 tick、水平飞行 1.15 倍、距离上限 24、额外寿命上限关闭。impact-strength=0.4 对应完整鱼竿向量：水平 0.50，地面/空中 Y=0.34；设为 0.2 使用一半推力。其他 profile 的 rod 默认仍为 3/1 tick、水平飞行 1.10。方向来自鱼钩飞行，不借用持竿者疾跑或击退附魔，不受近战高度锁定影响。新钩子归当前区域拥有时即时设置飞行速度；否则在首个 owned tick 设置一次。

碰撞通过 THROWN 类型的真实 damage(0) 发起受击判定，直接来源为 FishHook，攻击者为持竿者。2.0.5-r2 沿用 2.0.5 的反馈顺序：伤害判定、一次经典 KB、一次 PlayerVelocityEvent、立即发送速度、补固定方向受击动画与声音；撤回 2.0.6 将反馈提前的改动。取消伤害/KB/速度事件时不强补受击效果，清理本次杆的临时保护。默认不扣血，第三方监听器仍可改写伤害。

不清空已有近战免疫。连续杆实际间隔为 max(hit-interval-ticks, rod-hit-interval-ticks)，接刀再与 rod-melee-interval-ticks 取最大值，默认10 tick。旧短杆配置保留，但不再缩短共同受击窗口。T～T+9 拒绝重复杆/刀，T+10接受新的有效命中；不续期、不排队。正在执行的杆伤害在进入事件前就保护同一目标，防止嵌套监听器叠击。

新建零伤害杆免疫恢复为完整窗口，默认 noDamageTicks=20，第10 tick降到10。正伤害免疫和外部保护保留。classic 在钩子区域使用固定0.3碰撞余量和核心方块截断线段，从首个调度 tick 检查飞行杆，改善现代动态余量导致的近距离横移漏判；不预测/回溯移动，不扩展近战。现代模式使用原生处理。立即发送后的速度不在下一 tick 重放。源码依据、配置及复测见 [鱼竿修复](CLASSIC-ROD-STACK.md)。

鱼钩、持竿者、受击者必须同时由当前 Folia 区域拥有，否则不跨区操作受击者。2.0.5-r2 恢复 2.0.4 的鱼钩接触表现：钩子与鱼线保留，鱼钩以 `(-vx * 0.1, -max(0.05, abs(vy) * 0.1), -vz * 0.1)` 轻微向后、向下回弹，取消或免疫接触也遵守同钩一次的规则。回弹只改变鱼钩自身，不改变玩家的 KB 向量；不再原生挂载或仅对本人隐藏。鱼钩自身记录已接触状态，重复碰撞和保护期结束都不能让同一枚钩再造成KB。收杆间隔从实际收杆开始计时，距离/寿命限制和切模式清理继续生效。切刀时核心可正常结束钓鱼。CAUGHT_ENTITY 在原生拉回及客户端拉回状态包之前取消，并在收杆时移除鱼钩。钓物品和正常钓鱼走原生，modern 保留原版拉回。

## Classic 命中范围与互推

三种1.21.11核心的普通空手、剑、斧默认攻击距离均为3.0格；零网络容差下，目标身体最近点距眼睛2.99格可命中，3.01格不可命中。MKB保留原生距离，不将它改成2.99，不重写距离属性或物品攻击范围组件。长矛等专用物品范围、创造模式与第三方自定义范围继续由原生及对应插件决定。此结论不把服务器网络容差算作客户端攻击距离。

1.2.9移除1.2.8的挥手射线补判。原先身体额外膨胀0.1格再用3格射线，会对真实身体前沿在3.05格的目标补发攻击；移动连击中，服务端当前位置与客户端显示也可能不同，不能用挥空事件可靠重建原本的攻击目标。现在只处理原生攻击请求，不扩大身体、不调用额外 player.attack、不修改交互距离属性。classic 的旧版风格保留在无蓄力攻击、受击间隔、KB向量、疾跑/jump reset及禁止互推。

禁止玩家互推不能使用 collidable=false：三核心的 LivingEntity.isPickable 也读取该标记，进而影响 Projectile.canHitEntity。1.2.9 保留正常 collidable，只把其他在线玩家 UUID 加入 getCollidableExemptions；服务端因此排除玩家推挤而仍能命中鱼钩/箭。加入、退出、切模式和排除玩家时更新名单，恢复时只删除插件新增的项，保留原有第三方排除。旧1.2.8的保存标记与本版本未完成恢复的名单会在上线时清理/重建。所有实体操作在自己的区域执行。

客户端继续使用 collision=never 的队伍包副本，不修改真实计分板、名称样式或实际队伍成员；未分队的本地玩家使用临时客户端队伍。队伍增删、成员转移、bundle及modern恢复均跟踪。网络线程只处理包快照，不读取世界/实体。

## KnockbackSync 与 Modern

modern 读取 modern/config.yml 的 preserve-y 策略，蓄力、附魔、抗性、native/增量计算继续走现代路径。classic/combat.yml 的 protect-velocity-from-sync 默认 true，仅对本插件确认的经典命中保护当前速度，避免常规 KBS 垂直补偿覆盖 classic Y。2.0.4 在 PlayerVelocityEvent 开始时捕获实体已经应用的实际速度，结束时复核原伤害、KB事件、模式、排除状态与本次记录。保护事件向量，并在同步插件直接改了实体速度时立即恢复一次；相同速度不重复写入，不在后续 tick 重放。所支持核心的 setVelocity 不递归派发该事件。这样也保留了其他监听器对最终 KB 的修改，而非恢复较早的计算快照。

2.0.5 另处理 Leaf 的 `flush-location-while-knockback-player`：仅在原生近战立即刷新 tracker 的第二次同步速度事件中延续一次保护，并重新捕获当时速度；普通 tracker tick、插件主动刷新、后续 tick 和新命中不能借用该记录。通过读取选项和核对实际调用栈识别，不改核心配置，也不延长保护到下一 tick。

MKB 通过 softdepend 在 KBS 后注册，以 HIGHEST 完成本次保护，不更改 KBS 文件或全局启用状态。stop_sprint 的客户端状态路径只在 classic 生效；jump reset 只在 classic 的近战向量中生效。不同 ping 使用相同推力和受击窗口，按服务器已收到的有效状态处理；不补造命中或回溯移动。技巧关系、验证边界与对打复测见 [Classic 技巧与延迟](CLASSIC-TECHNIQUES.md)。

保护不恢复被取消的速度事件。其他插件若在 MONITOR 或发包阶段强行覆盖，仍可能改变结果。此版本未进行真实 KBS 联机对战，需在目标构建验收；Folia 需搭配支持 Folia 的 KBS。

## Java 接口

原 ModernKnockbackAPI、withHitType、KnockbackProfileEvent、KnockbackModifyEvent 继续可用。新增模式查询、切换及事件模式信息：

```java
ModernKnockbackAPI api = Bukkit.getServicesManager().load(ModernKnockbackAPI.class);
CombatMode mode = api.getMode();
// 切换含文件校验及磁盘保存，应从 async scheduler 调用。
Bukkit.getAsyncScheduler().runNow(myPlugin, task -> api.setMode(CombatMode.CLASSIC));
CombatMode hitMode = modifyEvent.getMode();
```

profile/exclude 共用 players.yml，参数取当前模式目录。狂暴连击、死神仅兼容已有名称，没有技能专属调参。KnockbackModifyEvent 始终提供 Paper 增量，即使 getMode() 为 CLASSIC 也不能直接当作最终速度 setVelocity。classic 计算的 isNativeCalculation()=false、fallbackReason=classic-vector；用 getMode() 区分路径。旧版本事件和配置构造器保留兼容入口。

## 实服验收

固定装备和场地，分别测试地面、空中、疾跑、击退附魔、弓箭、Bot、竿接刀、连续点击及多人围攻。classic 的重复 KB 组件计数增长正常，early hits 对应仍在免疫窗口内的点击。重启后检查 mode.yml、玩家排除和分配。

10 tick 是旧版伤害免疫风格，不代表每个点击都掉血；玩法需更快命中时，可改为 8 后对比。自动测试与三种核心 API 编译不能替代真人延迟、反作弊和其他插件参与下的手感测试。

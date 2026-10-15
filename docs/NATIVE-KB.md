# Modern 原生击退参数：推力与旧速度保留

modern 原生模式依据 LivingEntity 受击公式，将新增推力与旧速度保留分开计算，适用于 Minecraft 1.21.11 / Java 21+。modern 不重写攻击冷却。AIR_DRAG_MODIFIER、FRICTION_MODIFIER、BOUNCINESS 未在本地 1.21.11 Bukkit API 中公开，不直接引入这些字段。classic 的攻击节奏及速度保护见 [模式说明](COMBAT-MODES.md)。

## 解决的问题

1.0 对整个原生增量乘 H。普通原生水平公式为 `Vnew = 0.5 * Vold + Push`，所以旧做法变成 `Vnew = (1 - H/2) * Vold + H * Push`。降低 H 同时增加旧速度的保留，未必缩短每种运动状态下的位移。

1.1 可选原生模式先从事件恢复 `Push = Delta + 0.5 * Vold`，然后分别计算：

```text
Vnew.xz = retention * Vold.xz + horizontalMultiplier * extraMultiplier * Push.xz

地面：
Vnew.y = min(vertical-max-ground,
             vertical-retention-ground * Vold.y + K * vertical.ground * extraMultiplier)

空中 preserve：保持事件原有 Y 增量（匹配原生公式时约为 0）
空中 add：增加 max(0, min(K * vertical.air * extraMultiplier, vertical-max-air - Vold.y))

最后仍向 Paper 写回 Delta = Vnew - Vold，不调用玩家 setVelocity。
```

K 来自 Paper 提供的抗性处理后强度，不再次乘抗击退属性。推力方向从向量恢复，不读攻击者位置，不在 Folia 上跨区读取攻击者。空中 add 只补充正向抬升，不会为了满足上限强制压低已经超过上限的上升速度。

例如：受击前沿推力方向速度 1.0，原生推力 0.4，H=0.9，retention=0.5。旧模式得到 0.91；新模式得到 `0.5 + 0.36 = 0.86`。

## 配置

在 profiles.yml 的具名 profile 下启用；以下使用原版保留比例和地面 Y 上限，仅降低新增水平推力：

```yaml
profiles:
  player:
    native:
      enabled: true
      horizontal-retention-ground: 0.5
      horizontal-retention-air: 0.5
      vertical-retention-ground: 0.5
      vertical-max-ground: 0.4
      air-vertical-mode: preserve
      vertical-max-air: 0.4
    horizontal:
      ground: 0.95
      air: 0.90
    vertical:
      ground: 1.0
      air: 1.0
```

| 新字段 | 范围 / 默认值 | 含义 |
| --- | --- | --- |
| native.enabled | false | 启用普通受击原生公式模式 |
| native.horizontal-retention-ground | 0～1 / 0.5 | 地面旧水平速度保留比例 |
| native.horizontal-retention-air | 0～1 / 0.5 | 空中旧水平速度保留比例 |
| native.vertical-retention-ground | 0～1 / 0.5 | 地面旧 Y 速度保留比例 |
| native.vertical-max-ground | 0～4 / 0.4 | 地面公式计算后 Y 的上限；0 就是上限为0，不代表不限 |
| native.air-vertical-mode | preserve / add；默认 preserve | 原生空中保留，或额外补充抬升 |
| native.vertical-max-air | 0～4 / 0.4 | add 模式允许补充到的 Y 上限；不会降低已有更快上升 |

原 horizontal/vertical 和 modern 字段在 native 成功时作用于新增推力，不再乘旧速度保留项。modern 的疾跑/暴击/弱击倍率仍由已有快照判定。`vertical.air` 在 preserve 模式下不创造新的空中 Y。

`limits.horizontal/upward/downward` 的旧含义保持不变：它们在计算后限制增量，0 不限。这与 native.vertical-max-ground 的最终速度上限不同。增量限幅可能削弱公式需要的减速量；要严格观察原生公式，保持 limits 全部为0。扩展 API 监听器也可以进一步改变计算结果。

## 事件边界与兼容

- 只对现代 Paper 层级的 `com.destroystokyo.paper.event.entity.EntityKnockbackByEntityEvent` 的普通受击原因尝试恢复公式。这个类属于 Paper 事件继承体系，不是 Bukkit 已废弃的同名事件。
- 排除 EXPLOSION 和 PUSH：爆炸、风弹、箭矢冲击附加推力继续使用增量模式，避免套错公式。一次箭命中可能既有普通受伤组件又有附加 PUSH，各自仅处理一次。
- 本插件显式作用域内的鱼竿碰撞是已知 native knockback 调用，虽然没有实体来源子事件，也可以从向量推导 K 并核对 Y 后使用新模式。其余无强度元数据的普通事件退回增量模式。
- 恢复前检查水平推力长度与事件强度、Y 与地面/空中公式是否一致，容差用于 float 精度。发现其他插件/核心改动、无效值或零强度时退回旧的增量倍率，不强行覆盖成原版公式。该检查不能证明不存在所有外部改动，只能排除明显不匹配。
- modern 的 KnockbackSync preserve-y 在原生公式与增量计算两条路径中都优先，且扩展事件执行后仍恢复原 Y。需要自定义近战 Y 时要明确使用 before-sync，但最终 Y 仍可能被 KnockbackSync 替换。
- 只在受击者当前拥有区域读取其速度，立即修改事件增量。没有异步玩家速度修改、没有下一 tick 补打。
- 后续空气阻力、地面摩擦、攻击者命中后减速、无敌帧、攻击冷却、鱼竿收杆拉回不属于这次公式扩展，保持服务器原有处理。

## 升级与诊断

替换旧 Jar，确保服务器 plugins 中仅有一个 ModernKnockBack Jar，正常重启加载。旧 config.yml、profiles.yml、players.yml 无需删除；旧根目录配置迁移到 modern/，缺失的内置项按当前模板补全。显式设置的 native.enabled=false 保留，不自动覆盖已有值，详见 [配置补全](CONFIG-COMPLETION.md)。

要启用原生计算，在 modern/profiles.yml 的对应 profile 中设置 native 段，或采用 `presets/balanced-modern` 两份配置。执行 `/kbtest modern`；已经使用modern时执行 `/mkb reload`。保留已分配给玩家的自定义 profile 定义，原子保存与严格校验仍生效。狂暴连击、死神没有专属调整。

`/mkb status` 增加三项累计计数（启动后累计，不随 reload 清零）：

- Native formula：最终成功提交的新公式次数。
- Impulse fallback：启用了 native 但回退增量模式的次数，包括正常的 PUSH/EXPLOSION。
- Formula mismatches：回退中由公式数据、强度或数值检查失败造成的次数；它是 fallback 的子集。

扩展事件 `KnockbackModifyEvent` 新增 `isNativeCalculation()`、`getFallbackReason()`。原因包括 unsupported-event、strength-mismatch、vertical-mismatch、invalid-velocity、non-finite-result；原模式及 native 成功时原因为空串。旧事件构造器保留。

已增加移动惯性、抗击退、地面上限、空中策略、连续组件、箭矢 PUSH、爆炸排除、原配置兼容和 KnockbackSync Y 所有权的回归测试。API 编译验证不能替代真人联网 PvP 测试，调手感仍应在目标核心和实际插件组合下验证。

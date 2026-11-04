# Classic 中保留现代物品攻击（2.4.1 起）

编辑服务器的 `plugins/ModernKnockBack/classic/item.yml`：

```yaml
modern-items:
  - WOODEN_SPEAR
  - STONE_SPEAR
  - COPPER_SPEAR
  - IRON_SPEAR
  - GOLDEN_SPEAR
  - DIAMOND_SPEAR
  - NETHERITE_SPEAR
  - MACE
```

这是 1.21.11 的全部 7 种长矛和重锤。可增删 Bukkit 材质名，大小写不敏感，也接受 `minecraft:mace`。`modern-items: []` 关闭所有例外。升级会生成缺失文件/列表键，不补回已经主动删除的物品。

## 攻击与击退

- 以攻击者主手物品判断，不看受击者持有什么。普通攻击与玩家长矛刺击的攻击前事件均可识别；核心原生长矛攻击距离、刺击/冲锋组件及重锤下落伤害继续由核心处理。
- 白名单武器移除本插件的 Classic 攻速加成及旧斧头伤害修正，使用原生攻击冷却和服务器疾跑资格。换回普通物品立即恢复 Classic 攻速；重载时在各玩家所属实体区域刷新。
- 2.5.0 整合 [PR #2](https://github.com/ImMengXin0/ModernKnockBack/pull/2)，副手交换使用交换后的主手物品立即更新；装备更新、物品栏槽变化和插件替换物品也按实际主手更新。长矛突刺/突进的冷却判定不会继续沿用上一把刀剑的攻速；所有入口遵循此列表，移除长矛例外后也会恢复 Classic 行为。区域外事件不会读取玩家物品栏或属性。
- 白名单近战命中不使用 Classic 的固定向量、额外受击间隔拦截、jump reset、CPS 密度或重复 KB 组件合并。恢复进入 Classic 前保存的目标免疫参数，原生伤害/附魔/特殊武器的各 KB 组件仍按 Modern 处理，不清空无敌帧、不额外补造攻击。
- 两种免疫参数不同时，转换普通免疫窗口会保留已经过去的 tick，避免切武器提前解除保护或反复续期；已结束的窗口及更长的出生/其他插件保护不重新计时。
- 普通物品、鱼竿和箭矢继续走原来的路径。白名单是直接武器命中的例外；发射箭矢后切重锤、用 API 指定鱼竿/技能类型，不会误变成现代近战。
- 为保留 Classic 鱼竿防叠刀，鱼竿命中后的接武器保护仍约束白名单武器。Classic 全局玩家互推关闭、固定受击倾斜和视听效果过滤仍生效；物品例外不是切换整个服务器模式。

## Modern 参数来源

读取 `modern/config.yml` 的路由、matchups、玩家 profile 优先级和 KnockbackSync 策略，以及 `modern/profiles.yml` 的 `defaults` 与对应 profile：

| 攻击物品/类型 | 默认 Modern profile |
| --- | --- |
| 重锤 | `mace` |
| 全部长矛 | `spear` |
| 新加入的普通刀剑/斧头 | `player` / `bot`，横扫走 `sweep` |

例如在 `modern/profiles.yml` 调整重锤和长矛的水平增量倍率：

```yaml
profiles:
  mace:
    horizontal:
      ground: 1.0
      air: 1.0
  spear:
    horizontal:
      ground: 1.0
      air: 1.0
```

合并到已有 `profiles`，不要重复根节点。未写字段继承该文件的 `defaults`；不会读取 `classic/profiles.yml` 中同名 profile 的经典向量。Modern 的 `enabled: false` 保留原生 KB，仍保持物品的原生攻击方式。玩家分配的 profile 若在 Modern 中存在且启用 `respect-player-profile`，按已有优先级使用；否则回退类型路由。

## 重载和接口

在 Classic 中执行 `/mkb reload` 或 `/kbtest reload`，同时重载 `classic/item.yml`、Classic 配置和列表依赖的 Modern 配置。整套校验通过后才发布运行快照；错误材质、错误 YAML 或无效 Modern 参数会拒绝本次应用，保留已运行状态。空列表无需读取 Modern 配置。重启保留列表，Modern 模式不受此文件限制。

`/mkb status` 显示例外数量、配置路径和例外使用的 KnockbackSync 策略。公开 `KnockbackProfileEvent` / `KnockbackModifyEvent` 的 `getMode()` 对例外命中返回 `MODERN`，`HitContext.weapon()` 提供攻击时保存的材质名称；旧构造签名仍可调用。全局 API `getMode()` 仍返回服务器当前的 `CLASSIC`。

换物品时不会重置原生冷却计数；不同武器的剩余蓄力由核心按当前属性计算。本功能不替换客户端动画、原生物品组件或其他玩法插件的攻击逻辑。

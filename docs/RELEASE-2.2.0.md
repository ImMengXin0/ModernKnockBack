# ModernKnockBack 2.2.0

基于原始 2.0.5，整合 classic 技巧限制、鱼钩飞行配置和旧版命中反馈。适用于 Minecraft 1.21.11 / Java 21+，支持 Leaf、ASPaper、Folia，并保留 KnockbackSync 兼容处理。

## 更新内容

- **Jump reset 下限**：限制保留动量中的反向分量，沿本次命中方向至少保留实际水平推力的 25%；保留正常跳跃与侧向走位收益。
- **CPS 密度**：按当前或前一 tick 中较密集的一组实体攻击尝试，最多额外减少自身新近战受击水平退速的 20%，与 jump reset 共用上述下限。默认开启 `click-density-enabled`，不会缩短受击间隔或增加攻击次数。
- **鱼钩重力倍率**：新增 `rod.gravity-multiplier`，范围 1.0～4.0，默认 1.0；推荐用 1.5 作为加快下落的调试起点。classic / modern 分别读取各自配置，仅对尚未命中的空中鱼钩补充向下加速度。
- **旧版命中反馈**：新增默认开启的 `legacy-hit-effects`，隐藏现代伤害指示粒子及强击、弱击、暴击、击退、无伤害攻击提示音，保留变红、受伤声、挥手、暴击/附魔粒子和固定受击晃动。
- 新配置自动补全并保留已配置数值；保留鱼竿防叠 KB、竿接刀保护、原生挂钩、防互推和模式切换恢复。公开配置构造器保留兼容入口。

## 升级

1. 正常停服，使用 `ModernKnockBack-2.2.0.jar` 替换旧版本插件 Jar，并保留插件数据目录。
2. 正常启动，使用 `/mkb status` 核对版本和模式；用 `/kbtest classic` 启用 classic。
3. `classic/combat.yml` 自动补全 `click-density-enabled: true` 和 `legacy-hit-effects: true`。已有显式值保留；修改配置后使用 `/mkb reload`。

想加快 classic 鱼钩下落，可在 `classic/profiles.yml` 的实际使用配置中设置：

```yaml
profiles:
  fishing_rod:
    rod:
      gravity-multiplier: 1.5
```

## 验证与范围

310 项自动测试通过。对应运行逻辑已通过 Leaf 1.21.11-178、ASPaper 1.21.11、Folia 1.21.11-14 的真实运行库编译和离线底层探针，覆盖数据包、模式恢复、速度同步及鱼竿回归。

本版只修改插件。完整 1.8 持刀和模型动画需要客户端支持；离线检查不等同于真人 PvP、网络延迟或实服手感验证。20% / 25% 限制针对本插件计算时的向量，不能约束其他插件后续修改速度。详见 [验证记录](VERIFICATION.md)。

附件包含插件 Jar、此标签的完整项目源码 ZIP，以及两份文件的 SHA256 校验值。

# ModernKnockBack 2.5.0

Classic 默认让全部 7 种长矛与重锤保留高版本攻击方式，KB 从 Modern 模块读取。修复切换、副手交换或插件替换武器后攻速同步不完整，导致长矛突刺/突进继承 Classic 无冷却的问题。

- 合并 DevXingMC 的 [PR #2](https://github.com/ImMengXin0/ModernKnockBack/pull/2)，保留贡献者提交。它补齐切槽、副手交换、主手装备变化及物品栏变化时的同步处理，保留原生基础攻速和其他插件的属性修正。
- 整合 `classic/item.yml` 可配置物品例外：默认木、石、铜、铁、金、钻石、下界合金长矛及重锤；自定义物品同样适用。所有装备入口统一读取列表，删除物品或设 `modern-items: []` 后按 Classic 处理。
- 白名单近战读取 `modern/config.yml`、`modern/profiles.yml` 对应类型和基础参数，恢复原生冷却与武器机制，跳过 Classic 向量、CPS 减退及 KB 组件合并。两个模块的免疫参数转换保留已过 tick，避免切换武器提前解除保护或续期。鱼竿接武器保护仍生效。
- `/mkb reload` 或 `/kbtest reload` 同时校验例外列表和依赖参数，错误配置保留已运行状态；`/mkb status` 显示例外数量及对应同步策略。
- 补充修复排除玩家或 Modern 状态下切换装备仍遗留自身 Classic 攻速的入口，并在读取物品栏/属性前检查 Folia 区域所有权。
- 保留既有鱼竿、jump reset、CPS 密度上限、首次初始化预热和无需配置的网络发送补丁。

升级：正常停服，替换旧 Jar 为 `ModernKnockBack-2.5.0.jar` 后启动。已有配置保留，缺失的 `classic/item.yml` 自动生成；全局默认模式和已有模式选择保留。物品例外不关闭 Classic 的全局玩家防互推、固定受击倾斜或视听效果过滤。

支持 Minecraft 1.21.11 / Java 21+；验证记录见 [VERIFICATION.md](VERIFICATION.md)，配置见 [ITEM-COMBAT.md](ITEM-COMBAT.md)。验证包含自动化测试和三核心离线检查，真实客户端及第三方玩法插件组合仍需目标服验收。

# ModernKnockBack 3.1.0

- 修复启用 KnockbackSync Bukkit `1.3.6-dev-b142` 后，重启服务器的首轮近战可能出现的击退延迟。Classic 和 Modern 都会在启动时预热 KBS 的方块状态映射。`/mkb status` 和 profiler 会显示该版本 KBS 的实际全局同步开关。
- `/kbtest profiler open` 现在记录受击玩家本人速度包的向量、本地写出时间和接下来 6 tick 的逐 tick 位移。离线报告能区分速度事件开始前的等待和事件内的处理耗时，Modern 普通玩家近战还会显示受伤反馈包与速度包的提交间隔。

在 Leaf 1.21.11 build 168 的双客户端对照测试中，重启后第一刀从收到受伤反馈包到收到击退包的间隔，Classic 从 260.08 ms 降至 19.21 ms，Modern 从 270.72 ms 降至 23.70 ms。结果来自启用上述 KBS 版本的隔离服。

升级时用 `ModernKnockBack-3.1.0.jar` 替换旧插件并完整重启服务器。已有配置可以继续使用。

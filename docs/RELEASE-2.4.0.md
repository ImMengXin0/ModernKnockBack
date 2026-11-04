# ModernKnockBack 2.4.0

减少服务器重启后第一次战斗的插件初始化开销，并减少本人击退包在服务端批量发送中的等待。支持 Minecraft 1.21.11 / Java 21+，无需新增或重置配置。

- 合并 DevXingMC 的 [PR #1](https://github.com/ImMengXin0/ModernKnockBack/pull/1)，保留贡献者提交历史：战斗类及纯计算启动预热、显式命中提交/清理回调、非旧版斧头跳过物品附加数据读取。
- 保留 Leaf 速度派发识别及受击动画包反射预热，避免相关首次调用发生在第一刀或网络线程。
- classic / modern 自动及时刷新本人速度包与爆炸包，保留包顺序和单次写入。不会重发旧速度、按 Ping 修改 KB 或延长受击窗口；TCP 负责真实网络丢包重传。
- 保留既有 KB 参数、鱼竿判定及受击间隔、jump reset 和 CPS 密度上限。

升级：正常停服，替换旧版 Jar 为 `ModernKnockBack-2.4.0.jar` 后启动。无需删除插件配置目录。正式插件不包含独立 QA 探针。

验证与边界见 [验证记录](VERIFICATION.md)、[首击初始化](FIRST-HIT.md)和 [网络发送补丁](NETWORK-DELIVERY.md)。PR 作者的 Paper 性能测量单独保留在 [来源记录](FIRST-HIT-WARMUP.md)，不代表此版本在实际服务器上的帧时间或公网延迟。

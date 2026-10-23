# 配置自动补全

替换 Jar 并正常重启即可为当前模式补齐缺失配置，升级无需手工粘贴新增字段。/kbtest classic、/kbtest modern 会补全目标模式，/mkb reload 或 /kbtest reload 会补全当前模式。未加载的另一模式保持原文件，加载时再补全。

## 文件与规则

补全模式目录下的 config.yml、profiles.yml，以及 classic/combat.yml；缺失整个文件也会恢复。mode.yml 缺少选择时补为modern。players.yml 和升级前留在根目录的旧 config/profiles 不参与补全，玩家分配及排除状态不变。

配置模板来自当前 Jar 内置资源。缺失或空项补入模板值，已有非空值优先，包括0、false和自定义数值；未知字段、错误类型、非法范围、错误路由和无法解析的 YAML 仍按原来的严格校验报错。补全不会以默认值掩盖这些错误。

profiles.yml 的 defaults 补齐公共参数，缺失的内置 profile 名称补入。已有普通或自定义 profile 保留继承结构，不给每个 profile 复制整套默认参数。classic 的 fishing_rod 补出独立 classic/rod 段：已有本段值优先，其次沿用升级前在 defaults 明确写出的继承值，其余缺失项采用当前模板。

例如用户 defaults 已配置 horizontal-ground=0.5274、horizontal-air=0.5274、Y=0.361375，升级保留这些数值。fish profile 中缺失水平项时采用这两个旧轴，而不是用新 horizontal 简写覆盖；缺失 impact-strength 且 defaults 也未设置时补为0.4。已有强度0、0.2或其他数值仍保留。已经写出的抛投/收杆间隔和飞行速度同样保留，全新安装才直接使用整套当前推荐值。

旧版 reset-sprint-on-hit 仍作为 stop_sprint 同义别名加载：文件已有旧字段时不再补一个相反的 stop_sprint。两个别名已经存在且矛盾时继续报错。classic.horizontal-ground/air 存在时不补会覆盖两轴的 classic.horizontal 简写。

## 保存与恢复

整套目标模式先在内存中补全、校验，通过后才写文件。每个有变化的原文件先原文备份到 plugins/ModernKnockBack/backups/ 对应模式子目录，再采用原子保存；根 mode.yml 备份位于 backups/。文件名包含 before-completion 和本次唯一标识，控制台显示补全条目数量及备份位置。

保留已有普通注释、行内注释和仅有注释的空文件；新字段带入内置说明。YAML 输出可能统一缩进和数值文本格式，原始格式可在备份查看。已补全后再次加载不重写文件，也不重复创建备份。

发现编辑器在读取后改动了待写文件时，停止本次补全。无法创建备份时不写配置；中途写入失败会将已写文件恢复为本次备份前原文，并保留备份。重载失败保留原运行模式和配置，模式切换仍在目标加载成功后才持久化选择。实际加载新值不依赖上次内存，重启后读取补全后的文件。

## 安装与验证

替换为2.0.5-r1并正常重启；需要 classic 时执行 /kbtest classic，无需删除配置。该版以 2.0.5 为基础恢复此前鱼钩轻微回弹，保留固定受击角度和 Leaf 同步修复，不新增调参项，已有 KB 数值保留。缺失的 rod 间隔字段自动补为10，已有合法值保留。实际连续杆取 hit 与 rod-hit 的较大值，接刀取 hit、rod-hit、rod-melee 三项最大值。旧10/4/4因此执行10 tick保护，文件中数值仍保留；/mkb status显示实际值。详见 [鱼竿修复](CLASSIC-ROD-STACK.md)。

专项回归覆盖完整classic补全、鱼竿强度落盘、旧轴与继承值、false/0、自定义路由及profile、注释、旧疾跑别名、重复加载不写、不合法文件保留、备份失败、中途写失败回滚、并发编辑保护、缺失文件恢复及重启加载。最终构建与Leaf/ASPaper/Folia 1.21.11随包API检查结果附在发行包日志中。未在实际服务器部署或进行真人PvP测试。

当前验证结果见 [验证记录](VERIFICATION.md)。本地 dist 的构建及核心检查日志按发行版本命名；Jar 与源码包的 SHA256 见发行附件 SHA256SUMS.txt。

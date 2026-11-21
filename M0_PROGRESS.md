# 开发进度

> 本文件是 `M0`–`M4` 的历史流水与工程记录，**不再承载新计划**。
> `0.2` 的开发计划、测试与验收见 [`docs/02-development-plan.md`](docs/02-development-plan.md)。

更新时间：`2026-11-21`

当前目标：Minecraft `26.3`、Fabric 首发、Java `25`。NeoForge 只保留后续适配，
不作为 `0.1` 门禁。

**M0、M1、M2、M3 已完成；M4 发布候选已完成实现与短时验证，24 小时浸泡为发布操作者门禁。**

- M0 验收：[`docs/validation/M0.md`](docs/validation/M0.md)
- M1 验收：[`docs/validation/M1.md`](docs/validation/M1.md)
- M2 验收：[`docs/validation/M2.md`](docs/validation/M2.md)
- M3 验收：[`docs/validation/M3.md`](docs/validation/M3.md)
- M4 验收：[`docs/validation/M4.md`](docs/validation/M4.md)

## Git 状态

- 当前分支：`main`
- 发布点：tag `v0.1.0-beta`，与 `gradle.properties` 的 `version` 一致
- 发布流程：推送 `v*` tag 后由 `.github/workflows/release.yml` 构建并创建 GitHub
  Release；产物只有 Fabric jar，NeoForge 模块不随本版发布
- 发布说明按版本存放于 `docs/releases/<tag>.md`，工作流缺少该文件时拒绝发布

## 已完成

### 工具链与工程

- 已安装并使用 OpenJDK `25.0.5.1`。
- 已生成 Gradle wrapper `9.7.1`。
- 已建立 `core`、`mcp`、`fabric`、`neoforge` 模块和共享 `client/` 源码目录。
- Version 基线：
  - Minecraft `26.3`
  - Fabric Loader `0.20.1`
  - Fabric API `0.165.2+26.3`
  - Fabric Loom `1.14.10`
  - Java `25`
  - NeoForge spike `26.3.0.101` / ModDevGradle `2.0.148`
- `gradlew.bat` 在 `core.autocrlf=true` 下工作区为 CRLF，索引与远端仍为 LF；
  `git diff --check` 通过。

### Windows 开发环境（2026-11-20 重建）

原进度记录基于 macOS。当前实际开发机为 Windows 11，已重建并验证：

- JDK：Temurin `25.0.5.1+2`，解压到 `%USERPROFILE%\.jdks\jdk-25.0.5.1+2`。
- 已持久化用户环境变量：`JAVA_HOME` 指向该 JDK，`PATH` 前置 `%JAVA_HOME%\bin`。
- Gradle wrapper 首次运行已下载 `gradle-9.7.1-bin.zip` 到默认
  `%USERPROFILE%\.gradle`。
- 已验证通过的 Windows 命令：

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.jdks\jdk-25.0.5.1+2"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat --version          # Gradle 9.7.1 / Launcher JVM 25.0.5.1
.\gradlew.bat :core:test :mcp:test
.\gradlew.bat :fabric:compileJava
.\gradlew.bat build
```

- 本次结果：`core`、`mcp` 测试通过；`fabric` 编译通过；`./gradlew build` 全仓成功，
  产出 `core`、`mcp`、`fabric`、`neoforge` 四个模块的 jar。
- 因此文档中"全仓 `./gradlew build`"这一 M0 门禁已在 Windows 上达成。

### Core 与 MCP

- `core` 提供 Minecraft-free 的 `PlatformState`。
- `mcp` 提供最小 MCP `2025-11-25` Streamable HTTP 端点：
  - `/mcp`
  - Bearer Token
  - 仅允许回环地址
  - `initialize`
  - `tools/list`
  - `tools/call`
  - M0 工具：`ping`、`state`
  - 最大请求体、未知工具、认证失败和停服清理
- `:core:test :mcp:test` 已通过。
- 真实 Fabric 专用服 MCP E2E 已验证：
  - `initialize` 成功
  - `tools/list` 返回 `ping`、`state`
  - `state` 返回 `minecraftVersion=26.3`、`serverReady=true`
  - 无 Token 返回 HTTP 401
  - 控制台 `stop` 后 `8765` 端口释放

### Fabric

- 已注册 `tlmm:debug_companion` 测试实体。
- 实体包含：
  - 不同步的持久化探针字段
  - `SynchedEntityData` 同步字段
  - `ValueOutput` / `ValueInput` NBT 读写
- `M0SavedData` 使用 `SavedDataType` 和 CODEC。
- 占位 renderer 使用原版 `HumanoidMobRenderer`、`HumanoidModel` 和 zombie 纹理。
- `:fabric:compileJava` 已通过。
- `:fabric:runGameTestServer` 已真实运行并通过：
  - 实体注册
  - 同步字段
  - NBT round trip
  - SavedData 获取、递增和 dirty 状态
  - 日志结论：`All 3 required tests passed`
- Fabric 专用服已实际启动、生成世界、启动和关闭 MCP、保存世界并正常退出。

### NeoForge spike

- NeoForge 模块曾完成编译和一次 GameTest 启动。
- 后续发现实体属性警告，已补 `EntityAttributeCreationEvent`，但该修复尚未重新运行
  NeoForge GameTest。
- 按照当前优先级，不把 NeoForge 重新验证作为 M0 阻塞项。

## M0 门禁状态：已完成

`docs/validation/M0.md` 记录了完整命令、结果、版本与 Go/No-Go 结论。摘要：

- 全仓 `.\gradlew.bat build` 通过，四个模块 jar 均产出。
- `:core:test :mcp:test` 通过。
- `:fabric:runGameTestServer` 两次运行均 `All 3 required tests passed`；SavedData 基线值
  `0` → `1`，跨启动持久化成立。
- `:fabric:runClientGametest` 在真实客户端创建世界、生成 `tlmm:debug_companion` 并产出
  截图 `0000_m0-fabric-render.png`，画面确认占位模型可见。
- 真实专用服 MCP E2E：`initialize`、`tools/list`（仅 `ping`/`state`）、`ping`、`state`、
  401（无/错 Token）、未知工具、未知方法全部符合预期。
- 停服清理：同一次运行内出现 `[mcp] listening` → `All 3 required tests passed` →
  `[mcp] stopped`；运行后 TCP `8765`/`25565` 无监听、无残留 `java` 进程。

### Client GameTest 阻塞的根因与修复

原记录判断为"卡在标题界面、world builder 时序问题"，实测并非如此。

- 真实现象：进程以 `exit value -1073741819 (NTSTATUS 0xC0000005)` 退出，即原生访问
  冲突，发生在 GUI 初始化阶段，无 Java 异常与 crash report。
- 根因：本机 OpenGL 上下文报告 `3.3.0 Core Profile`（RX 7900 XT 预期 4.6），
  Minecraft `26.3` 的 `renderpearl` 渲染层在该路径下崩溃。普通 `runClient` 同样崩溃，
  证明与 Client GameTest 代码无关。
- 修复：使用 Minecraft `26.3` 的 `--graphicsBackend` 参数。`fabric/build.gradle` 新增
  可选属性 `tlmm.graphicsBackend`，仅在显式传入时转发：

```powershell
.\gradlew.bat :fabric:runClient -Ptlmm.graphicsBackend=vulkan
.\gradlew.bat :fabric:runClientGametest -Ptlmm.graphicsBackend=vulkan
```

- 对照实验：删除截图与 `options.txt` 后，不传属性仍崩溃；传 `vulkan` 则通过
  （`Using graphics backend Vulkan … AMD proprietary driver 26.9.2`）。
- 默认行为未变，不影响 CI 与其他机器。

### MCP 依赖结论

- Numen `v0.1.5-26.3-beta` 的公开 Maven 坐标未找到。
- M0 使用项目自己的最小纯 JVM MCP 层（`mcp` 模块，无 Minecraft 依赖）。
- M2 再决定 vendor Numen `mcp` 模块或继续维护最小层。

### 剩余收尾

- 检查 `git status`、完整 diff、`git diff --check`。
- 使用 Conventional Commit 创建 `M0` 完成提交。

## M1 进度：已完成

验收记录：[`docs/validation/M1.md`](docs/validation/M1.md)。

- `core` 新增 `LocalBehavior`、`CompanionInventoryLayout`、`CompanionSnapshot`、
  `CompanionHunger`、`CompanionServerConfig`；配置与饥饿共 15 个单测。
- `CompanionEntity`：owner、名字、UUID、本地行为、home、9 格背包、6 格装备、
  生命/饥饿/进食/饥饿伤害、低血量安全反射、守卫目标与自卫。
- `canAttack` 排除主人、玩家、村民和有主人的宠物。
- `tlmm summon|remove|rename|home|behavior`；命令与界面删除前都要求背包和装备为空。
- 右键 `CompanionMenu`：9 格背包、6 格装备、行为切换、home、删除二次确认、改名。
- 服务端配置 `config/touhou-little-maid-mini-server.toml`，含默认值、校验与回退。
- 行为、home、半径、背包摘要和 MCP 连接状态同步到客户端。
- GameTest 共 26 个全部通过（含绕障碍跟随）；真实客户端 Gametest 截图
  `0000_m1-companion-render.png`、`0001_m1-companion-screen.png`，界面按钮点击后
  服务端行为切到 `guard`。

## M2 进度：已完成

已完成并验证：

- `core` 新增 `ActionKind`（move/observe/interact/inventory/wait/stop）、`ToolSpec`、
  `ToolResult`（明确区分 ok / rejected / timeout / interrupted / unknown_tool）。
- `mcp` 新增 `ToolRegistry`（注册、参数类型校验、数值下限、未知参数拒绝、异常结构化）
  与 `CompanionTools`（8 个工具，低于 10 上限）。
- `McpServer` 改为由注册表驱动，并生成真实 JSON Schema 与 annotations。
- 修复 Gson 数字解码：原先把 JSON 整数解成 `LazilyParsedNumber`，导致合法的
  整数参数被误判为类型错误；现在规范化为 `Long`/`Double`。
- 修复未知同伴：原先落入 `internal_error`，现在是明确的 `unknown_companion`。
- `fabric` 新增 `FabricCompanionBackend`：所有世界操作提交到服务器主线程并等待结果，
  HTTP 线程不直接修改世界。
- `neoforge` 跟随接口变化同步：`NeoForgeCompanionBackend` 明确返回
  `unsupported_loader`，不伪造成功。

验证：

- `:core:test :mcp:test` 共 41 个测试通过（9 core + 32 mcp）。
- `:fabric:runGameTestServer` → `All 17 required tests passed`。
- 真实专用服 MCP E2E：工具数 10、与 `docs/04-mcp-contract.md` 名称一致、
  `invalid_params`、`unknown_companion`、审计日志无 Token 本体。
- 全仓 `.\gradlew.bat build` 通过。

工具面（与契约文档对齐，共 10 个）：

`companion_list`、`companion_state`、`companion_context`、`companion_inventory`、
`companion_set_behavior`、`companion_move`、`companion_interact`、
`companion_wait_events`、`companion_say`、`companion_stop`。

已实现：

- 事件总线 `EventBus`：单调递增序号、保留窗口、游标过期报 `event_cursor_expired`。
- `behavior_changed` 与 `operation_stopped` 事件。
- `TokenScope`：read-only 与 full 两档；写工具越权返回 `scope_denied`。
  `TLMM_MCP_READ_ONLY=1` 切换。
- `AuditLog`：只写 request id、token 摘要、工具名、companion、状态、code、耗时。
- 请求幂等：相同 `request_id` 重放不二次执行。
- `docs/mcp-development-setup.md`：Token 生成、启动、连接与冒烟步骤。

尚未完成（M2 收尾，进入 M3）：

- `companion_context` 的完整实现已移入 M3（分类、标签与实体过滤已完成）。
- `companion_inventory` 的 `action` 与装备槽已在 M3 实现。
- 事件长轮询是即时返回，尚未真正挂起等待 `timeout_seconds`。
- `companion_interact` 目前只处理成熟 `CropBlock` 收获；其他方块交互返回
  `unsupported_interaction`。
- Token 按同伴授权、撤销与过期属于 M3。
- 客户端 MCP 连接状态显示。

M2 结论：**Go**。协议、认证、作用域、审计、幂等、事件、线程桥接与作物收获全部
实测通过。验收记录见 `docs/validation/M2.md`。

关于 `companion_interact`：早期曾误判为"Minecraft 26.3 需要 Player 上下文而无法
实现"。正确依据是原版 `HarvestFarmland` 使用
`ServerLevel.destroyBlock(pos, dropItems, entity)`，该入口接受任意 `Entity`，
同伴实体同样适用。已按此实现并由 GameTest 覆盖。

## M3 进度：已完成

验收记录：[`docs/validation/M3.md`](docs/validation/M3.md)。

已完成并验证：

- `ContextCategory`、`ContextQuery`：七类上下文、半径/结果上限钳制、标签与 ID 归一化。
- `ToolSpec` 支持字符串数组参数；MCP schema 输出 `array` + `items`，参数解码支持数组。
- `companion_context` 支持 `category`、`radius`、`tags`、`block_ids`、`entity_types`、
  `max_results`；未加载区块以 `unloaded`/`partial` 明确报告。
- Fabric 后端实现 `self`、`owner`、`inventory`、`blocks`、`entities`、`containers`、
  `hazards`；`containers` 返回槽位内容，上锁容器按锁码只报 `locked=true`。
- `companion_inventory` 实现 `list/read/move/equip/drop`，支持背包、双手与四个护甲槽；
  不会凭空生成物品，`missing_item`、`inventory_full`、`busy` 均为显式结果。
- `companion_inventory` 映射到 `INVENTORY` 动作族，只读 Token 不能再调用写动作；
  `companion_context category=inventory` 仍可用于只读观察。
- `companion_move` 支持 `position/block/entity`、`goto/away`、停止距离、`replace` 与
  `busy`；`companion_interact` 支持目标类型、左右键、主副手和 `sneak`。
- 收获成熟作物后可从手部种子补种，复用原版 `HarvestFarmland` 语义；未成熟作物、
  unsupported 交互和失败均有明确 code。
- `companion_interact target_kind=entity` 支持掉落物拾取：遵守背包容量，部分拾取会把溢出
  留在原地，`inventory_full`/`not_ready` 为显式结果；非物品实体与空气交互明确 unsupported。
- `CompanionSchedule`（all/day/night）参与 follow/guard 判定，随实体持久化并同步；通过
  `companion_set_behavior` 的 `schedule` 参数设置，Agent 的显式请求不受日程过滤。
- `companion_wait_events` 真正挂起 HTTP 线程直到该同伴的新事件或超时；服务端主线程
  不等待。事件按同伴过滤，游标与保留窗口语义不变。
- 事件通过有界队列写入按大小滚动、按天数清理的 `logs/tlmm-events-*.log`，不会阻塞
  服务器线程；配置项为 `event_retention_days`、`event_max_file_mb`。
- 按同伴签发、撤销和过期 Token：`/tlmm mcp token create|list|revoke`；服务端只保存
  SHA-256 摘要，工具调用按 Token 作用域过滤 `companion_list` 并拒绝越权同伴。
- Token 摘要通过 `TokenSavedData` 持久化，服务端重启后已签发的 Token 继续有效。
- 破坏性 `drop` 动作经过 `PermissionBroker`：首次返回 `permission_required`，游戏内
  可点击的 `/tlmm auth allow|deny <id>` 由 owner 或 OP 决定；允许只消费一次，拒绝在
  有效期内持续生效。
- 断线宽限期由 `AgentPresence` + `AgentWatchdog` 实现：超过 `disconnect_grace_seconds`
  后停止导航并切到配置的安全模式；`continue_operation_after_disconnect` 可保留操作。
- Skill 示例：`skills/core/harvest_crops`、`organize_inventory`、`collect_items` 可执行；
  `fishing` 明确标注需要自定义 `Projectile` 钓鱼钩，不伪造完成。
- 测试：`:core:test :mcp:test` 通过；`:fabric:runGameTestServer` →
  `All 47 required tests passed`。

明确的交互边界见 [`docs/validation/M3.md`](docs/validation/M3.md) 第 4 节：原版
`FishingHook` 与 `Entity.interact` 都要求 `Player`，空气与钓鱼留待后续 L2 provider。

## M4 进度：发布候选

验收记录：[`docs/validation/M4.md`](docs/validation/M4.md)。

已完成并验证：

- 客户端：`CompanionModel` 在原版人体网格上增加蝴蝶结，原创 128x128 皮肤；行走与
  空闲呼吸动画；名字常驻；头顶第二行显示生命、饥饿、MCP/本地和当前行为。
- 管理界面重排为 176x240，增加心形/食物图标、MCP 状态点、地址复制按钮、复制反馈、
  悬停槽位高亮和 MCP/家/日程提示。
- 中英文语言文件扩展到 64 个键；`:fabric:verifyTranslations` 校验两语言键集合一致。
  命令反馈使用 `translatableWithFallback`，服务器控制台仍有英文回退。
- 配置新增 `config_version`、`[limits]`、`[actions]`、`[safety]`；缺少版本时自动补全并
  重写；无效值回退并记录原因。`[actions] enabled` 通过工具注册表过滤真正生效。
- MCP 端点由配置 `enabled` 和可用 Token 决定启动；不再强制 `TLMM_MCP_TOKEN`。有按同伴
  Token 但没有环境 Token 时也能启动。`[limits]` 已接入并发、单 Token 频率和认证失败
  IP 限制；`ping` 返回 MSPT、堆、线程与事件序列。
- 新增 `/tlmm diag`、`ServerDiagnostics`、`scripts/e2e-mcp.ps1`、`scripts/soak.ps1`。
- 版本升级到 `0.1.0-beta`；新增 `LICENSE`、`THIRD_PARTY_NOTICES.md`、
  `docs/07-installation.md`、`docs/08-licenses.md`、`docs/09-known-limitations.md` 和
  GitHub Issue 模板。
- 新增测试专用 `tlmm:test_crop`（仅 GameTest 注册），证明未出现过的 `CropBlock`
  也能用同一套通用动作收获和补种，不需要新工具或新 Java Task。
- `M4CropGameTests` 与 `M4PerformanceGameTests` 加入 GameTest 入口。

验证结果：

- `:core:test :mcp:test` 通过；新增配置迁移、`[limits]`/`[actions]` 解析、并发/频率/
  认证失败限流测试。
- `:fabric:runGameTestServer` → `All 49 required tests passed`。12 名同伴探针（8 待命 +
  4 由通用 `companion_move` 驱动，两波共 8 次移动）多次运行：平均 MSPT 约 `0.29–0.46`，
  单次最大约 `1.40–7.32`，均低于 `50/250` 预算；最近一次 `heap=411/8092MB`、
  `threads=33`、`events=30`（同批次其他测试会让 `server_companions=13`）。
- `:fabric:runClientGametest '-Ptlmm.graphicsBackend=vulkan'` 通过；截图
  `0000_m4-companion-render.png`、`0001_m4-companion-screen.png` 已人工检查，模型、
  名字、状态行和 240 高阶界面均可见且未被裁切。
- `:fabric:verifyTranslations` → `Translation keys match: 64 keys`。

24 小时浸泡尚未在本机执行。发布操作者必须在目标专用服运行
`.\scripts\soak.ps1 -Minutes 1440 -IntervalSeconds 60` 并保存 CSV；短时验证不能替代该门禁。
首次客户端启动若出现 `0xC0000005`，删除客户端缓存后重试；本机第二次运行已稳定通过。

## M6 进度：0.2 模块框架与模块库（M6.0–M6.2）

验收记录：[`docs/validation/M6.0.md`](docs/validation/M6.0.md)、
[`docs/validation/M6.1.md`](docs/validation/M6.1.md)、
[`docs/validation/M6.2.md`](docs/validation/M6.2.md)。

- `core/plan`：`ActionModule`、`ModuleSpec`、`Plan`、`PlanJson`、`PlanGate`、
  `PlanRunner`、`PlanPredicate`、统一失败码；计划体积 16KB、24 步、400 动作、
  12000 tick、128 破坏的上限全部可测。
- `fabric/plan`：`PlanRuntime` 每 tick 推进一个计划并把
  `plan_started / step_started / step_finished / plan_finished` 写入现有事件总线；
  `/tlmm debug plan <json>`、`/tlmm debug plancancel`。
- 模块：`say`、`goto`、`dig_block`、`place_block`、`withdraw_items`、`deposit_items`、
  `move_slot`、`craft_item`、`smelt_items`、`harvest_area`、`plant_area`、`till_area`、
  `mine_material`、`chop_tree`、`tidy_chests`，共 15 个。
- 验证：`:core:test` 通过；`:fabric:runGameTestServer` → `All 77 required tests passed`；
  `verifyTranslations` 78 键一致。
- 未开始：`M6.3` 语义底座、`M6.3b` 标注闭环、`M6.4` 客户端脑、`M6.5` 自主模式、
  `M6.6` 学习闭环、`M6.7` 发布验收。

## 恢复工作

重启后先读取本文件。Windows 下从仓库根目录执行：

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.jdks\jdk-25.0.5.1+2"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"

git status --short
git log -3 --oneline --decorate

.\gradlew.bat :core:test :mcp:test
.\gradlew.bat :fabric:compileJava
.\gradlew.bat :fabric:runGameTestServer
.\gradlew.bat :fabric:runClientGametest '-Ptlmm.graphicsBackend=vulkan'
.\gradlew.bat build
```

本机开发客户端必须带 `-Ptlmm.graphicsBackend=vulkan`；不加会以 `0xC0000005` 崩溃。
即使带 Vulkan 仍崩溃时，先删除 `run/clientGametest/options.txt` 和截图缓存；陈旧
选项会导致同样的原生访问冲突。

M4 实现、短时 GameTest、客户端截图、双语校验和发布文档已完成。剩余发布门禁是操作者
在目标专用服执行 24 小时 `scripts/soak.ps1`，并保留 CSV 作为证据。服务端配置位于
`config/touhou-little-maid-mini-server.toml`，示例见 `config-examples/`。

macOS 旧环境（已不再使用，仅存档）：

```bash
export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:/usr/bin:/bin"
```

Windows 与旧记录的两处差异：

- 不再设置 `GRADLE_USER_HOME="$PWD/.gradle"`；Gradle 缓存默认落在
  `%USERPROFILE%\.gradle`。
- Gradle wrapper 用 `.\gradlew.bat`，不是 `./gradlew`；运行目录仍为仓库根 `run/`
  （Loom 的 `runDir('../run/client')` 相对 `fabric/` 解析）。

Client GameTest 检查文件：

```text
run/clientGametest/screenshots/
run/clientGametest/logs/latest.log
run/clientGametest/logs/debug.log
```

## 注意事项

- MCP 端点由配置 `enabled` 和是否存在可用 Token 决定；`runServer` 不再强制环境 Token。
- 本地 E2E 使用临时测试 Token；不要把实际 Token 写进仓库或日志。
- `run/`、`runs/`、`.gradle/` 和构建产物均被 Git 忽略。
- 重启前应正常停止 Fabric server 和 Client GameTest，避免世界锁或缓存锁残留。
- 最近一次验证中 GameTest 服务端与 Client Gametest 均正常退出；未发现遗留
  client/server 进程或占用端口。

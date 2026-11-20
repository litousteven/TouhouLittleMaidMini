# M0 临时进度

更新时间：`2026-11-20 21:20 CST`

当前目标：Minecraft `26.3`、Fabric 首发、Java `25`。NeoForge 只保留后续适配，
不作为 M0 门禁。

**M0 全部门禁已通过，验收记录见 [`docs/validation/M0.md`](docs/validation/M0.md)。**

## Git 状态

- 当前分支：`main`
- `HEAD`：`7f09dfe docs(m0): record Fabric validation checkpoint`
- 先前提交：`7182204 test(m0): add Fabric client render probe`、
  `d067d39 feat(m0): add Fabric platform probe`
- 未提交的 M0 收尾改动：`fabric/build.gradle`、`docs/validation/M0.md`、
  `M0_PROGRESS.md`、`README.md`。
- `M0` 完成提交尚未创建。

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

## M1 进度（进行中）

已完成并验证：

- `core` 新增 `LocalBehavior`（stay/follow/guard）、`CompanionInventoryLayout`（9 格背包 +
  6 格装备）、`CompanionSnapshot`，含 9 个纯 JVM 单测。
- `CompanionEntity`：owner、名字、UUID、本地行为、home（维度 + 坐标）、9 格背包、
  跟随/待机目标、`canAttack` 排除 owner。
- 背包持久化按槽位显式存取。原用 `storeAsItemList`/`fromItemList` 会压缩列表并
  丢失槽位索引，已被 GameTest 捕获并修复。
- `tlmm summon|remove|rename|home|behavior` 命令；删除前阻断非空背包。
- GameTest 共 10 个全部通过（3 个 M0 + 7 个 M1）。
- 真实客户端截图确认 `tlmm:companion` 渲染。

尚未完成：

- `guard` 行为的具体目标与自卫逻辑。
- 生命、饥饿、进食与安全反射。
- 装备槽实际实现（目前只有布局常量）。
- 右键管理界面。
- 服务端配置与服务端/客户端同步。
- `docs/validation/M1.md` 与 M1 完成提交。

## M2 进度（进行中）

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

尚未完成（M2 收尾）：

- `companion_context` 目前只按半径统计方块，尚未支持标签与实体过滤。
- `companion_interact` 只读取目标方块，尚未真正右键交互。
- `companion_inventory` 只读单槽，尚未支持 `action` 与装备槽。
- 事件长轮询是即时返回，尚未真正挂起等待 `timeout_seconds`。
- Token 按同伴授权、撤销与过期属于 M3。
- 客户端 MCP 连接状态显示。
- `docs/validation/M2.md` 与 M2 完成提交。

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
.\gradlew.bat :fabric:runClientGametest -Ptlmm.graphicsBackend=vulkan
.\gradlew.bat build
```

本机开发客户端必须带 `-Ptlmm.graphicsBackend=vulkan`；不加会以 `0xC0000005` 崩溃。

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

- MCP 开发端点默认关闭。Fabric Loom 的 `runServer` 只通过开发 VM 参数启用。
- 本地 E2E 使用临时测试 Token；不要把实际 Token 写进仓库或日志。
- `run/`、`runs/`、`.gradle/` 和构建产物均被 Git 忽略。
- 重启前应正常停止 Fabric server 和 Client GameTest，避免世界锁或缓存锁残留。
- 记录本文件时，Fabric server 已通过控制台 `stop` 保存世界并正常退出；卡住的
  Client GameTest 已通过 `SIGTERM` 结束。已确认没有遗留 client/server 进程，
  TCP `8765` 和 `25565` 均无监听。

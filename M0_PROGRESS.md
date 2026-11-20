# M0 临时进度

更新时间：`2026-11-20 19:04 CST`

当前目标：Minecraft `26.3`、Fabric 首发、Java `25`。NeoForge 只保留后续适配，
不作为 M0 门禁。

## Git 状态

- 当前分支：`main`
- `HEAD`：`d067d39 feat(m0): add Fabric platform probe`
- 已提交的规划切换：`b360d1f docs: prioritize Fabric for Minecraft 26.3`
- 当前尚未提交的文件：
  - `fabric/build.gradle`
  - `fabric/src/main/resources/fabric.mod.json`
  - `fabric/src/main/java/com/touhoulittlemaid/mini/fabric/gametest/M0ClientGameTests.java`
  - `M0_PROGRESS.md`
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
- `gradlew.bat` 已规范为 LF，`git diff --check` 在最后一次中间提交时通过。

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

## 当前未完成

### Client GameTest 阻塞

已新增 `M0ClientGameTests`，目标是：

1. 自动创建单人测试世界。
2. 在玩家视线前方生成 `tlmm:debug_companion`。
3. 调用 Fabric Client GameTest 截图 API。
4. 校验 PNG 存在且大于 1 KiB。

当前状态：

- `:fabric:compileJava` 通过，Client GameTest API 和 entrypoint 均可编译。
- `:fabric:runClientGametest` 能启动真实 Fabric 客户端。
- 日志确认 `fabric-client-gametest-api-v1`、`tlmm` 和 Client GameTest entrypoint
  已注册。
- 客户端已进入渲染线程并完成基础资源加载。
- 但测试目前停在标题界面，没有创建世界，也没有生成截图；需要继续诊断
  Fabric Client GameTest 的 world builder/启动时序。
- 本次 `runClientGametest` 因重启前手动终止而返回 exit code `143`；这是主动停止，
  不是一次完整测试结论。
- 系统级 `screencapture` 当前为黑屏，不能作为可靠验收证据；应继续使用游戏内截图 API。

### 剩余 M0 门禁

- 修复并跑通 Client GameTest 自动截图，确认测试实体在真实客户端可见。
- 运行整个仓库的 `./gradlew build`。
- 若时间允许，再次运行 Fabric GameTest，确认第二次启动能够读取已保存的 SavedData。
- 更新技术方案中的 M0 MCP 结论：
  - Numen `v0.1.5-26.3-beta` 的公开 Maven 坐标未找到。
  - 当前 M0 使用项目自己的最小纯 JVM MCP 层。
  - M2 再决定 vendor Numen `mcp` 模块或继续维护最小层。
- 创建 `docs/validation/M0.md`，记录命令、结果、版本和 Go/No-Go。
- 检查 `git status`、完整 diff、`git diff --check`。
- 使用 Conventional Commit 创建 `M0` 完成提交。

## 恢复工作

重启后先读取本文件，再执行：

```bash
# 从仓库根目录执行
export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
export GRADLE_USER_HOME="$PWD/.gradle"
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:/usr/bin:/bin"

git status --short
git log -3 --oneline --decorate

./gradlew :core:test :mcp:test
./gradlew :fabric:compileJava
./gradlew :fabric:runGameTestServer
./gradlew :fabric:runClientGametest
./gradlew build
```

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

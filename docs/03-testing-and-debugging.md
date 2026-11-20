# 测试、调试与本地环境

## 1. 本地环境

目标环境：

| 组件 | 版本 |
|---|---|
| Minecraft | `26.3` |
| Fabric Loader | `0.20.1` |
| Fabric API | `0.165.2+26.3` |
| Java | `25` |
| Gradle | 使用仓库 wrapper |
| Fabric Loom | `1.14.10` |
| MCP 客户端 | 支持 Streamable HTTP + Bearer Token |

首次准备：

```bash
java -version
./gradlew --version
./gradlew build
```

要求：

- `java -version` 必须显示 JDK 25。
- Gradle wrapper、Fabric 游戏运行和 GameTest 都使用同一 JDK 25 toolchain。
- 客户端、服务端和测试任务不得复用同一个运行目录。

推荐目录：

```text
run/client
run/server
run/gameTestServer
run/e2e
run/soak
```

## 2. 常用运行流程

代码骨架完成后，预期流程如下：

```bash
./gradlew runClient
./gradlew runServer
./gradlew runGameTestServer
./gradlew test
./gradlew build
```

专用调试服建议使用独立终端：

```bash
./gradlew runServer --no-daemon
```

客户端与 Agent CLI 分别使用另外两个终端，避免 Gradle 日志遮蔽 MCP 故障。

## 3. MCP 调试配置

### 3.1 游戏端

开发配置默认：

```toml
enabled = true
host = "127.0.0.1"
port = 8765
require_token = true
disconnect_grace_seconds = 60
continue_operation_after_disconnect = false
safe_mode_after_disconnect = "stay"
```

启动游戏后在游戏内创建调试 Token：

```text
/tlmm mcp token create --companion debug_maid --days 1
```

Token 明文只显示一次。示例环境变量：

```bash
export TLMM_MCP_TOKEN="一次性显示的实际Token"
```

不要把 Token 写进 `.codex/config.toml`、Git、启动日志或截图。

### 3.2 Codex 端

项目已经提供 [`.codex/config.toml`](../.codex/config.toml)。重点配置：

```toml
[mcp_servers.touhou_little_maid_mini]
enabled = true
required = false
url = "http://127.0.0.1:8765/mcp"
bearer_token_env_var = "TLMM_MCP_TOKEN"
startup_timeout_sec = 15.0
tool_timeout_sec = 90.0
default_tools_approval_mode = "writes"
supports_parallel_tool_calls = false
```

说明：

- `required = false`：未启动 Minecraft 时，不阻止 Codex 做普通代码工作。
- `bearer_token_env_var`：Token 从当前进程环境读取。
- `default_tools_approval_mode = "writes"`：非只读 MCP 工具需要审批。
- Codex 只加载受信任项目的 `.codex/config.toml`；首次启动需信任本项目。

启动 CLI 前确认环境变量：

```bash
test -n "$TLMM_MCP_TOKEN" && echo "token is set"
codex mcp list
```

在 Codex 会话内运行：

```text
/mcp
```

应看到 `touhou_little_maid_mini` 及十个同伴工具。

## 4. 运行时开关

开发期可提供以下系统属性，但发行版以服务端配置为准：

```text
-Dtlmm.mcp.host=127.0.0.1
-Dtlmm.mcp.port=8765
-Dtlmm.mcp.debug=true
-Dtlmm.events.trace=true
```

`debug` 只允许：

- 更详细的连接生命周期。
- 工具调用耗时。
- 主线程排队时间。
- 事件序号与消费位置。

不得记录 Token、完整背包、私聊内容或任意命令参数。

## 5. Java 调试

客户端调试端口：

```text
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
```

服务端调试端口：

```text
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5006
```

只在本机调试时优先绑定 `127.0.0.1`。不要在生产服安全配置中保留开放 JDWP。

推荐断点：

- MCP Tool 入口：确认认证和作用域。
- `CompanionActionFacade`：确认参数解码和幂等键。
- 服务端调度入口：确认调用从 HTTP 线程切回主线程。
- 身体操作状态迁移：确认不会重复发终止事件。
- 事件 journal：确认写入发生在结果确定之后。

## 6. 测试分层

### 6.1 单元测试

不启动 Minecraft：

- Token 哈希、过期和 scope。
- `request_id` 幂等。
- 工具 schema。
- 参数校验与错误码。
- 事件游标、排序和去重。
- 身体操作状态机。
- 配置和版本迁移。

### 6.2 GameTest / 集成测试

在无头服务端中验证：

- 实体生成、保存、重载。
- follow/stay/guard 基本路径。
- move/interact/inventory 通用动作。
- Agent 通过 Skill 组合收获、补种和拾取。
- 授权、拒绝、超时。
- 客户端不需要参与的逻辑。
- MCP Host 与同伴动作的接缝。

GameTest 必须有固定种子、固定场地和明确时限。失败日志应能复现位置、实体和 operation ID。

### 6.3 MCP 协议 E2E

使用独立测试服务器和真实 Streamable HTTP 客户端：

1. 初始化会话。
2. `tools/list`。
3. 调用只读状态工具。
4. 执行移动与交互并长轮询。
5. 重启 CLI 后继续使用游标。
6. 撤销 Token，下一次请求被拒绝。
7. 关闭服务端，客户端得到可识别错误。

覆盖：

- 缺少 Token。
- 错误 Token。
- 过期 Token。
- 错 companion scope。
- 超并发、超频率。
- 取消长等待。
- 工具 timeout。
- MCP 会话断开和重连。

### 6.4 真客户端 E2E

必须用真实客户端完成：

- 自定义实体可见、动画正常。
- 右键管理界面可操作。
- home、半径、背包、装备同步。
- Agent 设置本地行为或执行动作后，客户端状态实时变化。
- 游戏内授权框出现并由真人答复。
- 客户端断开不破坏服务端身体操作状态。

### 6.5 长稳与性能

| 场景 | 时长 | 规模 |
|---|---:|---:|
| 单同伴空闲 | 1 小时 | 1 |
| 单同伴农作 | 1 小时 | 1 |
| 多同伴待命 | 8 小时 | 8 |
| 多同伴工作 | 2 小时 | 4 活跃 + 4 待命 |
| 最终浸泡 | 24 小时 | 配置上限 |

记录：

- 服务端 MSPT。
- JVM heap 与线程数。
- MCP 请求数、失败率、P99 延迟。
- 事件积压。
- 主线程单次最长阻塞。
- 实体丢失、动作卡死和重复事件数量。

## 7. 安全测试矩阵

| 项 | 期望 |
|---|---|
| URL 中携带 Token | 拒绝 |
| 未带 Bearer Header | `401` |
| Token 作用域外同伴 | 拒绝 |
| 重复 `request_id` | 返回首次结果，不重复执行 |
| Agent 请求他人容器 | 进入真人授权或拒绝 |
| Agent 尝试任意命令 | 无此工具 |
| 非循环地址但未显式开启 | 不监听 |
| 错 Origin | 拒绝 |
| Agent 在授权界面自己批准 | 无入口 |
| 日志与审计 | 不含 Token 和私有正文 |

## 8. 调试场景脚本

### 8.1 最小连接

Agent 提示词：

```text
连接 Touhou Little Maid Mini MCP。先列出同伴，读取 debug_maid 的状态。
不要修改行为或执行动作；只汇报位置、生命、本地行为和 MCP 连接状态。
```

成功标准：只读工具完成，无授权弹窗，无世界变化。

### 8.2 跟随行为

```text
让 debug_maid 跟随我。确认 behavior_changed 后，等待 10 秒，
再读取状态并报告两者距离。最后把它切回 stay。
```

成功标准：只出现一个行为切换；同伴实际跟随；事件游标单调递增。

### 8.3 农作 Skill 流程

```text
读取 farm skill。查询 debug_maid 附近 16 格内带作物标签的方块，
只处理已经成熟的方块；用 move、interact 和 inventory 完成收获与补种。
每一步等待 operation 事件，最后汇报收获数量、种子数量和剩余背包空间。
```

成功标准：没有调用专门的 farm MCP 工具；需要授权时等待真人；不破坏未成熟作物；
完成后背包与真人观察一致。

### 8.4 适应新内容

准备两种作物：原版小麦和一个通过方块/物品标签表达成熟种子与可种植状态的新作物。

```text
分别收获两种作物。你没有它们的专用工具，必须根据上下文、标签和 Skill 完成。
```

成功标准：

- 不需要新增 Java 类、MCP 工具或重启服。
- Agent 能在第一次失败后读取错误并调整动作。
- 不能处理的部分明确报告“缺少可表达的交互”，不能静默跳过。

### 8.5 断线恢复

1. 开始一次 Agent 驱动的农作流程。
2. 终止 Agent CLI。
3. 等待超过 `disconnect_grace_seconds`。
4. 再次启动 CLI并查询事件。

成功标准：同伴停止外部操作，进入配置的安全模式；事件保留且不重复。

## 9. 故障排查顺序

1. 游戏是否已启动并注册同伴。
2. `enabled` 是否为真、Host/Port 是否正确。
3. 端口是否被占用。
4. 环境变量是否在启动 Codex 的同一 shell 中设置。
5. Token 是否过期或作用域不包含该同伴。
6. Codex 项目是否受信任，`.codex/config.toml` 是否被加载。
7. `/mcp` 是否能看到工具。
8. 工具是否因 `writes` 审批被暂停。
9. 游戏内服务端主线程是否卡住。
10. 查看审计中的 request ID、工具名、耗时和错误码。


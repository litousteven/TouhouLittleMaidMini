# MCP 开发连接

本文件描述如何从本地 Agent CLI 连接开发端或发布版的 MCP 端点。

## 1. 生成 Token

端点的启动不再强制要求开发环境 Token。只要存在至少一个可用 Token，端点就会按配置
启动：

- 游戏内按同伴签发（推荐）：`/tlmm mcp token create 7`。
- 开发环境静态 Token：`TLMM_MCP_TOKEN` 环境变量。

游戏内命令：

```text
/tlmm summon
/tlmm mcp token create 7          # 为当前拥有的同伴签发 7 天 Token
/tlmm mcp token list              # 查看 id、摘要 ID、到期时间与同伴
/tlmm mcp token revoke <token_id> # 立即撤销
```

规则：

- Token 只存在于环境变量、签发时的聊天提示和进程内存中；服务端只保存 SHA-256 摘要。
- 摘要写入世界 `TokenSavedData`，服务端重启后已签发的 Token 继续有效。
- 不要把 Token 写进 `.codex/config.toml`、Git、日志或截图。
- 开发 Token 拥有所有同伴；签发的 Token 只允许访问发放时选中的同伴。
- 破坏性动作（当前为丢弃物品）会要求游戏内真人确认：`/tlmm auth allow <id>` 或
  `/tlmm auth deny <id>`；确认窗口由 `[safety] owner_confirmation_timeout_seconds` 控制，
  一次授权只消费一次。

## 2. 启动端点

推荐先在游戏内签发 Token，再重启或重载服务器。日志出现下面这行即表示端点就绪：

```text
[tlmm] [mcp] listening on http://127.0.0.1:8765/mcp
```

如果没有可用 Token，日志会明确提示：

```text
[tlmm] [mcp] not started: no bearer token exists; create one with /tlmm mcp token create <days> ...
```

开发环境也可以只靠环境变量：

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.jdks\jdk-25.0.5.1+2"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:TLMM_MCP_TOKEN="一次性开发 Token"
.\gradlew.bat :fabric:runServer
```

可选环境变量：

| 变量 | 作用 |
|---|---|
| `TLMM_MCP_READ_ONLY=1` | 静态开发 Token 只授予 observe 类工具权限 |

端点总是绑定回环地址。`config/touhou-little-maid-mini-server.toml` 的 `[limits]` 控制：

- `max_concurrent_calls`：同时在途的工具调用数。长轮询会占用一个名额。
- `max_calls_per_minute_per_token`：单 Token 每分钟调用数，超出返回 `rate_limited`。
- `max_failed_auth_per_minute_per_ip`：同一 IP 每分钟认证失败上限，超出返回 HTTP 429。
- `default_tool_timeout_seconds`：客户端调用工具时的参考超时预算。
- `default_event_wait_seconds`：`companion_wait_events` 省略 `timeout_seconds` 时实际使用的
  长轮询秒数（上限 120）。

`[actions] enabled` 决定暴露哪些动作族。删除一个动作族会同时移除归入它的工具；工具面
始终不超过 10 个。

## 3. 连接 Agent CLI

项目内已提供 `.codex/config.toml`：

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

`bearer_token_env_var` 让 CLI 从环境变量读取 Token，配置文件本身不含秘密。

## 4. 手工冒烟

```powershell
$h = @{ Authorization = "Bearer $env:TLMM_MCP_TOKEN"; "Content-Type" = "application/json" }

# 列出工具
Invoke-RestMethod -Uri "http://127.0.0.1:8765/mcp" -Method Post -Headers $h `
  -Body '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# 健康快照：MSPT、堆、线程、事件积压
Invoke-RestMethod -Uri "http://127.0.0.1:8765/mcp" -Method Post -Headers $h `
  -Body '{"jsonrpc":"2.0","id":2,"method":"ping","params":{}}'

# 列出同伴
Invoke-RestMethod -Uri "http://127.0.0.1:8765/mcp" -Method Post -Headers $h `
  -Body '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"companion_list","arguments":{}}}'
```

完整脚本：

```powershell
.\scripts\e2e-mcp.ps1 -Token $env:TLMM_MCP_TOKEN
```

脚本会在没有同伴时明确跳过同伴动作，而不是伪造成功。

## 5. 长稳与停服

短时检查：

```powershell
.\scripts\soak.ps1 -Minutes 5 -IntervalSeconds 30 -Token $env:TLMM_MCP_TOKEN
```

24 小时发布门禁使用同一脚本并保存 CSV：

```powershell
.\scripts\soak.ps1 -Minutes 1440 -IntervalSeconds 60 -Token $env:TLMM_MCP_TOKEN `
  -Output run/soak-24h.csv
```

在开发服控制台输入 `stop`。日志应出现：

```text
[tlmm] [mcp] stopped
```

停服后 `127.0.0.1:8765` 不再监听，GC 和事件队列不会把 Token 写入日志。

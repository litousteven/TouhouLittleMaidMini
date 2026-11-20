# MCP 开发连接

本文件描述如何从本地 Agent CLI 连接开发端的 MCP 端点。

## 1. 生成 Token

端点的启动仍需要一个开发环境 Token，仅通过环境变量传递：

```powershell
$env:TLMM_MCP_TOKEN = -join ((48..57) + (97..122) | Get-Random -Count 32 | ForEach-Object { [char]$_ })
```

进入游戏后可以签发按同伴授权的 Token（明文只显示一次）：

```text
/tlmm mcp token create 7          # 为当前拥有的同伴签发 7 天 Token
/tlmm mcp token list              # 查看 id、摘要 ID、到期时间与同伴
/tlmm mcp token revoke <token_id> # 立即撤销
```

规则：

- Token 只存在于环境变量、签发时的聊天提示和进程内存中；服务端只保存 SHA-256 摘要。
- 不要把 Token 写进 `.codex/config.toml`、Git、日志或截图。
- 开发 Token 拥有所有同伴；签发的 Token 只允许访问发放时选中的同伴。
- 当前 Token 存储是进程内内存，重启服务器后需要重新签发。
- 破坏性动作（当前为丢弃物品）会要求游戏内真人确认：`/tlmm auth allow <id>` 或
  `/tlmm auth deny <id>`；确认窗口默认 120 秒，一次授权只消费一次。

## 2. 启动开发服

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.jdks\jdk-25.0.5.1+2"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:TLMM_MCP_TOKEN="上面生成的串"
.\gradlew.bat :fabric:runServer
```

日志出现下面这行即表示端点就绪：

```text
[tlmm] [mcp] listening on http://127.0.0.1:8765/mcp
```

可选环境变量：

| 变量 | 作用 |
|---|---|
| `TLMM_MCP_READ_ONLY=1` | 只授予 observe 类工具权限，写工具返回 `scope_denied` |

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

# 列出同伴
Invoke-RestMethod -Uri "http://127.0.0.1:8765/mcp" -Method Post -Headers $h `
  -Body '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"companion_list","arguments":{}}}'
```

## 5. 停服

在开发服控制台输入 `stop`。日志应出现：

```text
[tlmm] [mcp] stopped
```

停服后 `127.0.0.1:8765` 不再监听。

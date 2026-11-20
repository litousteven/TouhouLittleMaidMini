# MCP 开发连接

本文件描述 M0-M2 阶段如何从本地 Agent CLI 连接开发端的 MCP 端点。

## 1. 生成一次性 Token

当前实现尚未提供 `/tlmm mcp token create` 命令。开发阶段用一个本地随机串作为
Bearer Token，只通过环境变量传递：

```powershell
$env:TLMM_MCP_TOKEN = -join ((48..57) + (97..122) | Get-Random -Count 32 | ForEach-Object { [char]$_ })
```

规则：

- Token 只存在于环境变量和进程内存中。
- 不要把 Token 写进 `.codex/config.toml`、Git、日志或截图。
- 每次重启开发服时重新生成即可。

按同伴授权、作用域、撤销和过期属于 M3 的交付内容；M2 只实现整端点一个 Token。

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

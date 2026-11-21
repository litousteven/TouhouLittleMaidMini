# 安装与首次连接

本文面向首次安装 `0.1.0-beta` 的玩家和服务器管理员。目标是：在干净环境中按本文
在 30 分钟内跑通 M2 验收场景（启动游戏、召唤同伴、Agent 通过 MCP 读取状态）。

## 1. 环境要求

| 组件 | 版本 |
|---|---|
| Minecraft | `26.3` |
| Fabric Loader | `0.20.1` 或更高 |
| Fabric API | `0.165.2+26.3` |
| Java | `25` |
| MCP 客户端 | 支持 Streamable HTTP + Bearer Token（Codex 等） |

服务端和客户端都需要安装本模组。MCP 端点只监听回环地址，不能从其他机器直接连接；
需要远程访问时请使用 SSH 隧道等外部手段，不要修改 `host` 绑定公网地址。

## 2. 安装模组

1. 从发布页下载 `touhou-little-maid-mini-fabric-26.3-0.1.0-beta.jar`。
2. 放入客户端或服务端的 `mods/` 目录。
3. 同时安装匹配版本的 Fabric Loader 与 Fabric API。
4. 启动一次游戏或服务端。首次启动会生成：

```text
config/touhou-little-maid-mini-server.toml
```

配置文件带 `config_version`。旧配置会在启动时补全并重写；无效值回退到默认值并在
日志中报告原因。完整字段见 [服务端配置示例](../config-examples/touhou-little-maid-mini-server.toml)。

## 3. 创建 Token

Token 有两种来源，任选一种：

### 3.1 游戏内按同伴签发（推荐）

进入游戏并召唤同伴后执行：

```text
/tlmm summon
/tlmm mcp token create 7
```

第二条命令会显示一次明文 Token，并给出 `token_id`。Token 只允许访问签发时
你拥有的同伴。服务端只保存 SHA-256 摘要，重启后仍然有效。

```text
/tlmm mcp token list
/tlmm mcp token revoke <token_id>
```

### 3.2 开发环境 Token

开发或自动化环境可以设置环境变量：

```powershell
$env:TLMM_MCP_TOKEN = "<一次性开发 Token>"
```

开发 Token 拥有全部同伴的完整作用域。不要把 Token 写进仓库、截图或 `fabric.mod.json`。

## 3.5 游戏内配置直连模型（可选，`0.1.0-beta`）

> `M6` 起模型调用迁移到玩家客户端，Key 只存客户端，服务端不再发起请求。
> 本节的命令与服务端凭据文件仅适用于当前 `0.1.0-beta`；升级后按
> [`docs/10-autonomy-and-scripting.md`](10-autonomy-and-scripting.md) 在客户端配置。

右键同伴打开管理界面，点击“AI 设置”，填写 OpenAI 兼容的 base URL、model name 和
API Token。也可以由 owner 或 OP 使用命令：

```text
/tlmm llm base <url>
/tlmm llm model <model>
/tlmm llm token <token>
/tlmm llm enabled true
```

base URL 应指向 chat completions 端点，例如
`https://api.openai.com/v1/chat/completions`。Token 只保存在服务端配置目录的独立凭据
文件中，不会出现在聊天回显、日志或审计记录；输入框只显示掩码。未配置模型时同伴仍
可执行跟随、驻守、守卫和作物收割。

## 4. 启动 MCP 端点

端点默认由配置中的 `enabled = true` 控制，但只有存在至少一个可用 Token 时才会启动。
启动后日志出现：

```text
[tlmm] [mcp] listening on http://127.0.0.1:8765/mcp
```

若日志显示 `[mcp] not started: no bearer token exists`，先签发 Token，再重启服务器。
若 `enabled = false`，端点不会启动。

## 5. 连接 Agent CLI

Codex 的项目级配置见 [`.codex/config.toml`](../.codex/config.toml)。核心字段：

```toml
[mcp_servers.touhou_little_maid_mini]
enabled = true
required = false
url = "http://127.0.0.1:8765/mcp"
bearer_token_env_var = "TLMM_MCP_TOKEN"
default_tools_approval_mode = "writes"
supports_parallel_tool_calls = false
```

Token 从启动 CLI 的同一进程环境读取。首次连接前确认：

```powershell
test -n "$env:TLMM_MCP_TOKEN"
codex mcp list
```

## 6. 30 分钟验收场景

在仓库根目录执行：

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.jdks\jdk-25.0.5.1+2"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:TLMM_MCP_TOKEN = -join ((48..57) + (97..122) | Get-Random -Count 32 | ForEach-Object { [char]$_ })

.\gradlew.bat build
.\gradlew.bat :fabric:runClient '-Ptlmm.graphicsBackend=vulkan'
```

1. 新建单人世界。
2. 在游戏中执行 `/tlmm summon`。
3. 在另一个终端执行：

```powershell
.\scripts\e2e-mcp.ps1 -Token $env:TLMM_MCP_TOKEN
```

脚本会依次执行 `initialize`、`tools/list`、`ping`、`companion_list`、
`companion_state`、`companion_context` 和 `companion_wait_events`。看到 `E2E PASS`
即完成 M2 场景。

本机若在客户端启动阶段出现 `0xC0000005`，先删除
`run/clientGametest/options.txt` 和截图缓存后重试，并确认启动参数包含
`-Ptlmm.graphicsBackend=vulkan`。这是本机 Vulkan 驱动的已记录问题，不是模组逻辑。

## 7. 专用服注意事项

- 专用服没有玩家时无法执行 `/tlmm summon`（该命令需要玩家上下文）。
- 协议和健康检查可以在无同伴时用 `scripts/e2e-mcp.ps1` 验证；脚本会明确提示跳过
  同伴动作。
- 停服时日志应出现 `[mcp] stopped`，并释放 `127.0.0.1:8765`。
- 长时间运行请使用 [`scripts/soak.ps1`](../scripts/soak.ps1) 记录 MSPT、堆、线程和
  事件积压；24 小时流程见 [`docs/03-testing-and-debugging.md`](03-testing-and-debugging.md)。

## 8. 故障提示

| 现象 | 检查 |
|---|---|
| `401 Invalid bearer token` | Token 是否来自当前服务器、是否过期或被撤销 |
| `429 Too many failed authentication attempts` | 等待一分钟，确认没有脚本重复使用错误 Token |
| `rate_limited` | 降低调用频率或等待长轮询结束；检查 `[limits]` |
| `scope_denied` | Token 没有该同伴或该动作族的作用域 |
| `permission_required` | 在游戏内点击聊天中的允许/拒绝，或执行 `/tlmm auth allow <id>` |
| `[mcp] not started` | 先签发 Token，再重启；确认 `enabled = true` |
| 端口被占用 | 关闭旧服务器；确认没有残留 `java` 进程 |

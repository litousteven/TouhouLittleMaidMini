# Minecraft 版本升级 Runbook

项目的目的之一是显著降低 Minecraft 大版本升级成本。升级是否成功，不看“服能启动”
一项，而看 Agent 的既有场景是否仍能完成。

## 1. 稳定层与版本层

### 稳定层

- `core/` 动作模型、事件、状态与配置 schema。
- `mcp/` 工具映射、认证、限流、审计。
- `skills/` 玩法知识。
- `data/` 与游戏版本无关的约定和测试夹具描述。
- MCP contract、错误码与事件游标语义。

### 版本层

- NeoForge Mod 入口和 DeferredRegister。
- 实体、Brain、Attribute、Navigation。
- 客户端 renderer、screen 和 payload。
- NBT、Attachment、SavedData 与注册表序列化。
- Minecraft 方块状态、标签和 GUI API 适配。

版本层代码不得反向污染稳定层。

## 2. 分支策略

首版采用：

```text
main       稳定层、文档、Skill、MCP 契约
mc/26.3    可发布版本分支
mc/<next>  下一游戏版本分支
```

规则：

- 稳定改进先合入 `main`，再合入各版本分支。
- 游戏 API 修复只在版本分支发生。
- Skill 与协议修复通常进入 `main` 后向版本分支合并。
- 禁止把整个 `mc/26.x` 游戏代码复制到 `main`。
- Release tag 使用 `<mod-version>+mc<minecraft-version>`。

如果以后版本分支数量使 cherry-pick 成本上升，再评估 Gradle composite builds 或独立
adapter 工程，不在首版提前复杂化。

## 3. 升级前准备

记录当前基线：

- NeoForge 版本。
- Fabric 版本（如果未来支持）。
- Java toolchain。
- Gecko 或其他客户端依赖版本。
- 当前 MCP 工具表哈希。
- 最近一次通过的 E2E 报告。
- 实体 NBT、SavedData 和事件日志版本。

创建升级分支：

```bash
git switch main
git switch -c mc/<version>
```

如果从旧版本分支复制，先对比稳定层文件，避免把过期实现带回来。

## 4. 升级顺序

### Step 1：构建骨架

- 更新 Minecraft/NeoForge/ModDevGradle/Java。
- 只让 `core` 与 `mcp` 编译通过。
- 运行全部纯 Java 单测。

任何 MCP schema 或 core 改动都必须有明确原因。版本升级本身不是修改协议的理由。

### Step 2：启动与注册

- Mod 主类、事件总线、DeferredRegister。
- 配置加载和资源 pack。
- 服务端与客户端空壳启动。

### Step 3：实体与持久化

- EntityType、Attribute、Brain、Activity。
- `ValueInput`/`ValueOutput`、Attachment、SavedData。
- 生成、保存、重载、删除。

### Step 4：客户端

- Entity renderer。
- Screen、Menu、payload。
- 资源、语言、模型与声音。

客户端 API 常是升级差异最大的区域，必须与实体分包。

### Step 5：动作执行器

- move、observe、interact、inventory、wait。
- 主线程调度。
- 取消、超时、权限和事件。

### Step 6：MCP 接缝

- 工具 schema 与 `main` 对比。
- Bearer Token 与 scope。
- 长轮询重连。
- 请求 ID 幂等。

### Step 7：Agent E2E

固定跑以下场景：

1. list/state/context。
2. follow/stay/guard。
3. 到指定方块并交互。
4. 使用通用动作收获一种作物。
5. 新作物通过标签与 Skill 适配，不新增 Java。
6. 权限拒绝与断线恢复。
7. 服务端重启后状态和事件游标。

## 5. 兼容差异表

每个升级分支必须填写：

| 项 | 旧版本 | 新版本 | 改动 | 验证 |
|---|---|---|---|---|
| NeoForge |  |  |  |  |
| Java |  |  |  |  |
| Entity 注册 |  |  |  |  |
| Brain API |  |  |  |  |
| Entity data |  |  |  |  |
| Renderer |  |  |  |  |
| Screen/Menu |  |  |  |  |
| Payload |  |  |  |  |
| Tags/Data |  |  |  |  |
| Client model |  |  |  |  |

没有差异的项写 `none`，不允许留空。

## 6. 回归门禁

版本升级完成前必须通过：

- Pure JVM 单测。
- MCP 认证、幂等、取消与 reconnect。
- GameTest：实体、保存、动作、权限。
- 真客户端：渲染、右键、面板、同步。
- 专用服 E2E：Codex 连接并完成任务。
- 8 同伴性能基线。
- 24 小时长稳。
- 无残留端点和后台线程。

## 7. Diff Budget

升级分支必须满足：

- `core/` 只有确有游戏无关 bug 时才变化。
- `mcp/` schema 无变化。
- Skills 无变化，除非平台语义真的改变。
- 用户配置默认向后兼容。
- 测试场景名和断言不因版本升级随意改动。

出现大范围稳定层改动时，先查是否把游戏适配混进了错误模块。

## 8. 数据迁移

升级前：

- 固定旧存档与事件日志。
- 用旧版本读取一次，生成基准快照。
- 用新版本读取副本。

必须验证：

- 同伴 UUID、owner、home、半径、行为。
- 背包和装备。
- Token 与 scope。
- 事件序号。
- 未完成操作的恢复策略。

迁移失败时保留旧文件，不原地覆盖。

## 9. 发布检查

- 版本号为 `<mod-version>+mc<minecraft-version>`。
- README 中支持矩阵更新。
- MCP contract 版本未变。
- Skill 与数据 pack 版本已记录。
- 新客户端只与明确声明的服务端版本互连。
- 旧客户端/新服务端或新客户端/旧服务端不兼容时有清晰拒绝原因。
- 变更日志把“游戏适配”和“Agent 能力”分开。

## 10. 版本升级失败条件

以下任一成立时，不发布：

- Agent 既有 Skill 必须重写才能完成原任务。
- MCP 工具表需要新增专用 Task 才能恢复基本玩法。
- 需要把每 tick 逻辑散落到多个实体类。
- 客户端 API 变化必须传播到 core/mcp。
- 数据迁移会覆盖旧存档。
- 相同 E2E 场景在不同机器上结果不稳定。


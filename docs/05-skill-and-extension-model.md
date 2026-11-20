# Skill 与模组适配模型

本项目的“智能”来自外部 Agent。为避免重新走回“每个模组写一批 Java Task”的路线，
适配必须按能力层级递进。

## 1. 四个适配层级

### L0：通用动作

适用于遵守原版交互语义的内容：

- 看到方块或实体。
- 走到交互距离。
- 右键、左键或使用手中物品。
- 读取背包变化。
- 等待结果。

这一层不需要任何模组专用代码。Minecraft 本身和大多数“能用玩家手完成”的内容都属于
这一层。

### L1：标签、数据与 Skill

需要理解“什么算成熟作物”“哪些是种子”或“这个机器应如何按顺序操作”时：

- 用 tags/IDs 描述对象。
- 用 Markdown Skill 描述流程、前置条件和失败恢复。
- 不改变 MCP 工具。

这是新增玩法的主要路径。

### L2：Java Action Provider

普通交互和 Skill 都无法可靠表达时：

- 只有少量、强类型、可权限检查的专用动作。
- 复用 Action、Event、Permission 和 Audit 的唯一入口。
- MCP 仍通过既有工具映射，不为每个 Provider 新增顶层工具。

例子：一个模组的服务端行为需要特殊参数或需要读取非原版状态。

### L3：Client Bridge

功能只存在于客户端 GUI、输入或渲染状态时：

- 新增受限的 client bridge。
- 服务端仍负责最终权限与权威状态。
- 客户端不能收到任意“执行这个 Java 方法”的请求。

这是成本最高、使用范围最小的层级。

## 2. Java 准入测试

新增内容必须先回答：

1. L0 通用动作能否完成？
2. 能否通过标签、ID、方块状态或实体状态完成判断？
3. 能否用 Skill 描述顺序与恢复？
4. 是否确实需要每 tick 控制？
5. 是否无法由服务端得到必要状态？

如果答案支持 Skill/数据方案，则不得新增专用 Java Task。

## 3. Skill 目录

建议结构：

```text
skills/
├─ core/
│  ├─ follow_owner/
│  │  └─ SKILL.md
│  ├─ harvest_crops/
│  │  └─ SKILL.md
│  └─ organize_inventory/
│     └─ SKILL.md
└─ compat/
   └─ <mod_namespace>/
      └─ <task_name>/
         └─ SKILL.md
```

Skill 属于外部 Agent 的内容，不进入游戏服务端模型请求。Codex、Claude Code 或其他 CLI
从工作目录、自身 Skill 系统或用户提示词加载它们。

## 4. Skill 元数据

建议 frontmatter：

```yaml
---
name: harvest-crops
description: Harvest mature crops and replant when seeds are available.
applies_to:
  - minecraft
  - "*"
requires_tools:
  - companion_context
  - companion_move
  - companion_interact
  - companion_inventory
game_versions:
  - "26.3"
---
```

`applies_to` 使用模组 ID 或通配符，避免把模组名称写进 Java。

## 5. Skill 正文

Skill 应包含：

- 目标与完成条件。
- 必要物品和工具。
- 先观察什么，不要求 Agent 偷看未加载状态。
- 动作顺序。
- 可公开观察的成功与失败信号。
- 可重试和不可重试错误。
- 权限、危险和停止条件。
- 一个最小示例。

农作示例：

```markdown
## Goal
Harvest mature crops in the configured radius and replant with the same crop's seed
when one is available.

## Procedure
1. Query `companion_context` with `category=blocks` and the mature-crop tag or block IDs.
2. Read `block_state`; only act on crops whose state means mature.
3. Move to each crop with `arrive_distance=2`.
4. Use the correct click or tool from the skill.
5. Collect dropped items with `companion_move` and `companion_interact`.
6. Select a matching seed through `companion_inventory`.
7. Replant into an air or replaceable block above suitable farmland.
8. Repeat until no mature crops remain or the action budget is exhausted.

## Stop conditions

- permission_required
- inventory_full
- missing_item
- companion hurt below the configured threshold
- owner asks to stop
```

Skill 不包含隐藏代码，不能要求模型执行 shell、Lua 或任意 Java。

## 6. 结果反馈

每步动作必须返回：

- 是否成功。
- 实际观察到的方块或物品变化。
- 玩家可见的错误原因。
- 可执行的下一条修复提示。

避免把整个背包或大范围世界反复回传。Agent 应按需调用上下文类别和过滤器。

## 7. 模组适配示例

### 普通模组作物

- 如果使用原版 `CropBlock` 语义：L1 数据 + Skill。
- 如果使用自定义成熟状态：L1 Skill 描述状态判定，必要时增加只读数据 provider。
- 如果必须通过客户端按钮收获：L3 bridge。

### 通用容器机器

- 如果客户端界面只是普通槽位：L0。
- 如果有配方与输入输出顺序：L1 Skill。
- 如果状态只在服务端私有对象：L2 只读 provider。
- 如果操作必须点击客户端按钮：L3 bridge。

### 战斗

- 选目标、何时撤退、武器策略：Agent + Skill。
- 每 tick 瞄准、冷却、近战攻击、安全反射：Java。

## 8. Skill 质量门

一个 Skill 进入仓库前需要：

- 至少一个原版或测试模组场景。
- 成功、缺物品、权限拒绝和部分完成案例。
- 不用内部 UUID 或不可观察状态作为唯一成功依据。
- 不依赖一个过大的单个工具输出。
- 由真实 Agent 跑一次 E2E，而不是只做文本 lint。

## 9. 反模式

- 为每种作物复制一遍 Skill，只改一个 ID。
- 把大多数逻辑写进一个“万能 act”命令。
- Skill 直接要求任意命令。
- 在多个 Skill 中复制权限与危险动作规则。
- 为了一个模型调用方便而新增固定工作模式。


# MCP 契约

版本：`tlm-mini-mcp/v1`

本文定义外部 Agent CLI 与模组之间的稳定边界。MCP 只是传输；业务实现必须使用强类型
Java record，文本 JSON 仅用于跨进程表示。

## 1. 通用规则

- 工具名：小写下划线。
- 所有写工具接受可选 `request_id`，建议使用 UUID。
- `request_id` 在 Token、companion 和工具名范围内幂等，保留 24 小时。
- 所有 ID 使用 Minecraft UUID 的小写字符串。
- 坐标使用 `{ "x": 0.5, "y": 64.0, "z": 0.5 }`。
- 距离单位是方块。
- 时间参数单位是秒；游戏刻只在事件内部附带时使用。
- 未列出的字段默认拒绝，避免模型把拼写错误静默忽略。

标准成功结果：

```json
{
  "ok": true,
  "code": "ok",
  "message": "Short result for the agent",
  "data": {}
}
```

标准错误结果：

```json
{
  "ok": false,
  "code": "permission_denied",
  "message": "The companion may not enter another player's home",
  "hint": "Ask the owner in game or choose another work area",
  "data": {}
}
```

## 2. `companion_list`

只读。

参数：

```json
{}
```

结果：

```json
{
  "companions": [
    {
      "companion_id": "6d3d...",
      "name": "Maid-01",
      "owner_id": "7e22...",
      "online": true,
      "behavior": "stay",
      "has_active_operation": false
    }
  ]
}
```

Token scope 之外的同伴不出现在结果中。

## 3. `companion_state`

只读。

参数：

```json
{
  "companion_id": "6d3d..."
}
```

结果：

```json
{
  "companion_id": "6d3d...",
  "name": "Maid-01",
  "dimension": "minecraft:overworld",
  "position": {"x": 120.5, "y": 64.0, "z": -35.5},
  "facing": "south",
  "health": 18.0,
  "max_health": 20.0,
  "behavior": "stay",
  "operation": {
    "operation_id": "op17",
    "status": "running",
    "action": "move",
    "progress": 0.42
  },
  "home": {"x": 116, "y": 63, "z": -38},
  "radius": 16,
  "inventory_used_slots": 4,
  "inventory_free_slots": 5,
  "event_seq": 42
}
```

不返回完整背包列表；需要时调用 `companion_context`。

## 4. `companion_context`

只读。按类别加载有限上下文，避免每轮复制整个世界状态。

参数：

```json
{
  "companion_id": "6d3d...",
  "category": "nearby_entities",
  "radius": 16
}
```

首版类别：

| category | 内容 |
|---|---|
| `self` | 装备、状态效果、饥饿、载具 |
| `owner` | owner 位置、距离、是否在线、是否受伤 |
| `inventory` | 物品、数量、装备、书与特殊容器摘要 |
| `blocks` | 按方块 ID、标签和半径返回已加载方块摘要 |
| `entities` | 按种类、标签和半径返回实体摘要 |
| `containers` | 已授权且可安全读取的附近容器摘要 |
| `hazards` | 岩浆、火、TNT、深坑和危险实体 |

`radius` 不会超过服务端配置的 `max_action_radius`。未加载区块返回 `unknown`，不能当作空。

方块和实体查询支持过滤：

```json
{
  "companion_id": "6d3d...",
  "category": "blocks",
  "radius": 16,
  "tags": ["minecraft:crops"],
  "block_ids": ["minecraft:wheat"],
  "max_results": 128
}
```

不提供专为模组编写的 `farm`、`machine` 等类别；Agent 使用标签、ID、方块状态和 Skill
理解未知内容。

## 5. `companion_set_behavior`

写工具。设置持续运行的本地行为，而不是某种农作或模组工作。

```json
{
  "request_id": "9df8...",
  "companion_id": "6d3d...",
  "behavior": "follow",
  "home": {"x": 116, "y": 63, "z": -38},
  "radius": 16,
  "schedule": "all"
}
```

`behavior` 首版允许：

- `follow`
- `stay`
- `guard`

`schedule` 允许 `all`、`day`、`night`。后续可以加入不重叠的时间区间。

结果：

```json
{
  "accepted": true,
  "behavior": "follow",
  "event_seq": 43
}
```

规则：

- 新行为先通过权限与前置条件检查。
- 已经处于相同行为且参数未变时返回 `no_change`。
- home 不在当前维度或未加载时返回 `invalid_home`。
- 半径被 `max_action_radius` 截断时，结果明确显示实际值。

## 6. `companion_move`

写工具。一次移动作为一个身体操作执行；长距离寻路不阻塞 HTTP 线程。

```json
{
  "request_id": "b820...",
  "companion_id": "6d3d...",
  "target": {
    "kind": "block",
    "position": {"x": 120, "y": 64, "z": -35}
  },
  "mode": "goto",
  "distance": 2.0,
  "replace": true
}
```

`target.kind` 首版支持：

- `position`
- `block`
- `entity`

`mode`：

- `goto`
- `away`

结果：

```json
{
  "accepted": true,
  "operation_id": "op17",
  "event_seq": 44
}
```

规则：

- 目标必须在加载区块内或可安全接近。
- 移动只能使用身体当前具备的能力；首版不做通用破坏和搭桥。
- 有活动操作且 `replace = false` 时返回 `busy`。
- 到达、失败、超时和取消均发事件。

## 7. `companion_interact`

写工具。执行一次玩家式交互，不包含“种植”“钓鱼”或某个模组的完整流程。

```json
{
  "request_id": "39c1...",
  "companion_id": "6d3d...",
  "target": {
    "kind": "block",
    "position": {"x": 120, "y": 64, "z": -35}
  },
  "button": "right",
  "hand": "main",
  "sneak": false,
  "replace": false
}
```

`target.kind`：

- `block`
- `entity`
- `air`

语义：

- `right` 等同右键使用；对实体表示交互或骑乘。
- `left` 首版只执行一次玩家攻击/点击语义。
- 是否需要靠近、能否看见以及实际结果由身体判断。
- 对他人容器、危险方块和保护实体仍经过权限层。
- 具体物品选择由调用方先通过 `companion_inventory` 准备好。

结果返回操作 ID；真实变化通过事件回传。

## 8. `companion_inventory`

写工具。处理背包、装备和普通容器，不负责理解模组专有 GUI。

```json
{
  "request_id": "5ea2...",
  "companion_id": "6d3d...",
  "action": "move",
  "item": "minecraft:wheat_seeds",
  "count": 8,
  "source": "inventory",
  "destination": "main_hand",
  "slot": 3
}
```

`action` 省略时为 `list`；只提供 `slot` 时按旧行为读取该背包槽。`slot` 省略时按
`item` 在 `source` 中查找第一个匹配项。`equip` 的 `destination` 必须是装备槽，
默认 `main_hand`。

`action`：

- `list`
- `move`
- `drop`
- `equip`
- `read`

支持的目标：

- `inventory`
- `main_hand`
- `off_hand`
- `head`
- `chest`
- `legs`
- `feet`
- 已打开的普通 container window

限制：

- 默认只能移动实际存在的物品，不能凭空生成。
- 目标槽不存在、物品不允许或背包无空间时原样返回。
- 复杂客户端 GUI、只有客户端存在状态的按钮、任意模组脚本不在本工具范围内。

## 9. `companion_stop`

写工具。

```json
{
  "request_id": "419a...",
  "companion_id": "6d3d...",
  "operation_id": "op17",
  "behavior": "stay",
  "reason": "owner changed the plan"
}
```

- `operation_id` 省略时停止当前身体动作。
- 同时可以切换本地行为；省略时不改变当前行为。
- 旧操作已结束返回 `already_finished`，不视为服务器错误。
- 停止后同伴不能继续执行未完成的外部计划。

## 10. `companion_wait_events`

长轮询，只读。

```json
{
  "companion_id": "6d3d...",
  "after_seq": 42,
  "timeout_seconds": 50,
  "max_events": 20
}
```

返回：

```json
{
  "events": [],
  "next_seq": 42,
  "timed_out": true
}
```

事件至少包括：

| kind | 主要数据 |
|---|---|
| `behavior_changed` | 旧行为、新行为 |
| `operation_started` | operation ID、action |
| `operation_progress` | action、progress |
| `operation_completed` | result、item delta |
| `operation_failed` | error code、retryable |
| `operation_stopped` | reason |
| `owner_hurt` | 伤害来源、距离 |
| `companion_hurt` | 伤害来源、生命 |
| `inventory_full` | 拾取失败摘要 |
| `permission_required` | 请求摘要、授权 ID、到期时间 |
| `permission_resolved` | 允许/拒绝 |
| `mcp_disconnected` | 断线宽限期开始 |

`event_seq` 必须单调递增。客户端重连时从自己的 `after_seq` 继续；服务端不得重复发送
已经确认消费的序号。保留期外的游标返回 `event_cursor_expired`，要求先调用
`companion_state` 重建状态。

## 11. `companion_say`

写工具。

```json
{
  "request_id": "4534...",
  "companion_id": "6d3d...",
  "text": "The wheat is harvested and replanted."
}
```

限制：

- 最多 280 字符。
- 不支持命令、富文本点击事件或任意翻译键。
- 默认只显示给 owner。

## 12. 错误码

| code | 含义 |
|---|---|
| `bad_argument` | 参数缺失、类型或范围错误 |
| `not_found` | 同伴或对象不存在 |
| `scope_denied` | Token 无权操作该同伴 |
| `permission_denied` | 规则拒绝 |
| `permission_required` | 已发起真人授权 |
| `busy` | 当前身体操作不允许替换 |
| `missing_item` | 缺少工具、材料或食物 |
| `invalid_home` | home 无效或不在加载范围 |
| `no_path` | 当前移动找不到安全路线 |
| `unsafe_area` | 环境不满足执行条件 |
| `timeout` | 操作或等待超时 |
| `event_cursor_expired` | 事件游标超出保留范围 |
| `internal_error` | 未分类服务端错误 |

错误文字应可直接交给模型修正；堆栈只进服务端日志。

## 13. 版本策略

- 工具名和已有必填参数在 `v1` 内保持稳定。
- 新参数以可选字段加入。
- 结果新增字段允许；删除字段需要新 schema 版本。
- `companion_wait_events` 的游标语义不得在 `v1` 内改变。
- 不兼容变化在 `initialize.instructions`、包内文档和服务端命令中同时标出新版本。


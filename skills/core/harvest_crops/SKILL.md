---
name: harvest-crops
description: Harvest mature crops and replant them with the matching seed.
applies_to:
  - minecraft
  - "*"
requires_tools:
  - companion_context
  - companion_move
  - companion_interact
  - companion_inventory
  - companion_wait_events
game_versions:
  - "26.3"
---

# Harvest crops

## Goal

Harvest every mature crop inside the work radius and replant it with the same crop's
seed when one is available. Do not break immature crops.

## Procedure

1. Ask `companion_context` for `category=blocks`, `tags=[minecraft:crops]`,
   `radius=<work radius>` and a bounded `max_results`.
2. Read each block's `properties.age` (and `block_ids` when the crop is known). Only act
   on crops whose block state means fully grown. If the state is ambiguous, use the
   block's `minecraft:age` property or another tag the world already uses; do not guess
   from the texture name.
3. Move to a block next to the crop with `companion_move`, `distance=2`.
4. Right-click the crop with `companion_interact`, `target_kind=block`.
5. If the result is `not_ready`, skip the block instead of retrying forever.
6. Before replanting, put seeds in the main hand with `companion_inventory`:
   `action=equip`, `item=minecraft:wheat_seeds`, `destination=main_hand`.
7. Right-click the air block above the farmland. A successful replant returns `planted`
   and consumes one seed.
8. Repeat until `companion_context` returns no mature crops, the action budget is spent,
   or the inventory has no seeds.

## Stop conditions

- `permission_required` — wait for the owner to answer the in-game prompt.
- `inventory_full` — report the free slots and stop.
- `missing_item` — report which seed or tool is missing.
- `unsafe_area` — stop body actions and ask the owner to heal the companion.
- `busy` — pass `replace=true` only when the current operation should be abandoned.
- The companion is hurt below the configured threshold.

## Example

```text
context blocks tags=[minecraft:crops] radius=16
move block (120,64,-35) distance=2
interact block (120,64,-35) button=right
inventory equip minecraft:wheat_seeds -> main_hand
interact block (120,65,-35) button=right
```

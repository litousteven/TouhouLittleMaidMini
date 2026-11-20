---
name: fishing
description: Fish with a rod by the generic block/air interaction surface.
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
status: draft
---

# Fishing

## Goal

Catch fish with a fishing rod without a dedicated Java task.

## Current limitation

`companion_interact` currently supports block targets only: mature crop harvest and
replanting. `target_kind=air` and non-crop blocks return `unsupported_interaction`.
Fishing therefore needs either the air-interaction action to grow to use a held item, or
a narrow L2 action provider that reuses the permission, audit and event entry points.

Do not claim a catch that was not observed. Report the limitation instead.

## Procedure when air interaction is available

1. Equip the rod with `companion_inventory action=equip item=minecraft:fishing_rod
   destination=main_hand`.
2. Move to water with `companion_move distance=2`.
3. Right-click air/water with `hand=main`.
4. Poll `companion_wait_events` for `operation_completed`.
5. When a bite is observed, right-click again.
6. Stop on `missing_item`, `unsupported_interaction`, `unsafe_area` or owner stop.

## Stop conditions

- `unsupported_interaction`
- `missing_item`
- `unsafe_area`
- Owner asks to stop.

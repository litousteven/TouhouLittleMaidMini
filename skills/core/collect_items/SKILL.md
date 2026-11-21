---
name: collect-items
description: Pick up nearby dropped items with the generic actions that exist in this build.
applies_to:
  - minecraft
  - "*"
requires_tools:
  - companion_context
  - companion_move
  - companion_interact
game_versions:
  - "26.3"
---

# Collect items

## Goal

Bring nearby dropped items into the companion's backpack.

## Procedure

1. Use `companion_context` with `category=entities`, `entity_types=[minecraft:item]`
   and a bounded `radius`.
2. Move to the nearest item with `distance=1`.
3. Interact with `target_kind=entity`, `entity_id=<uuid>`.
4. Read the result:
   - `picked_up` — confirm the item delta with `companion_context category=inventory`.
   - `not_ready` — the item still has a pickup delay; wait and retry once.
   - `inventory_full` — stop and report the free slots.
   - `unsupported_interaction` — the target was not a dropped item entity.
5. Repeat until no item entities remain or the action budget is exhausted.

## Limit

Only dropped item entities are supported. Interacting with a living entity (villager,
animal, another companion) returns `unsupported_interaction`, because vanilla
`Entity.interact` requires a `Player`. Do not pretend such an interaction happened.

## Stop conditions

- `inventory_full`
- `unsafe_area`
- Owner asks to stop.

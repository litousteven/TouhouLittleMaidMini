# Companion skills

Skills are content for the external agent, not code that runs inside Minecraft. They
describe how to combine the ten generic tools and data the world already exposes.

## Layout

```text
skills/
├─ core/
│  ├─ harvest_crops/SKILL.md
│  ├─ collect_items/SKILL.md
│  ├─ fishing/SKILL.md
│  └─ organize_inventory/SKILL.md
└─ compat/<mod_namespace>/<task>/SKILL.md
```

## Data conventions

- Crops: block tag `minecraft:crops`; growth from the block state's `age` property.
- Plantable seeds: block items whose block is a `CropBlock`, mirroring vanilla
  `minecraft:villager_plantable_seeds` and `HarvestFarmland`.
- Hostile entities: `Monster` entity types; never target players, villagers or owned pets.
- Containers: block IDs with a block entity; contents stay unread until a permission
  layer grants access.
- Unknown mods: prefer IDs, block tags, entity tags and block-state properties before
  asking for a new Java provider.

## Quality gate

A skill enters the repository only after a real Agent CLI run against a real server
covers success, missing item, permission denial and partial completion. Draft skills
state their unsupported steps instead of pretending they work.

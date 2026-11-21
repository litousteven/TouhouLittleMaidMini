package com.touhoulittlemaid.mini.core.semantic;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The closed set of semantic categories a model may propose.
 *
 * <p>Seventeen values, fixed. The model labels content; it cannot invent a category, a
 * number or a tag. Each value declares the kinds it is meaningful for, which is how
 * "item marked as station" gets rejected instead of silently accepted.
 */
public enum SemanticTag {

    CROP("crop", Set.of(SemanticKind.ITEM, SemanticKind.BLOCK)),
    SEED("seed", Set.of(SemanticKind.ITEM)),
    FOOD("food", Set.of(SemanticKind.ITEM)),
    RAW_FOOD("raw_food", Set.of(SemanticKind.ITEM)),
    FUEL("fuel", Set.of(SemanticKind.ITEM)),
    LOG("log", Set.of(SemanticKind.ITEM, SemanticKind.BLOCK)),
    PLANK("plank", Set.of(SemanticKind.ITEM, SemanticKind.BLOCK)),
    STONE("stone", Set.of(SemanticKind.ITEM, SemanticKind.BLOCK)),
    ORE("ore", Set.of(SemanticKind.ITEM, SemanticKind.BLOCK)),
    BUILDING_BLOCK("building_block", Set.of(SemanticKind.ITEM, SemanticKind.BLOCK)),
    STATION("station", Set.of(SemanticKind.BLOCK)),
    CONTAINER("container", Set.of(SemanticKind.BLOCK)),
    TOOL("tool", Set.of(SemanticKind.ITEM)),
    HOSTILE("hostile", Set.of(SemanticKind.ENTITY)),
    LIVESTOCK("livestock", Set.of(SemanticKind.ENTITY)),
    BREEDABLE("breedable", Set.of(SemanticKind.ENTITY)),
    DECORATIVE("decorative", Set.of(SemanticKind.ITEM, SemanticKind.BLOCK));

    /** Material categories that cannot describe the same content at once. */
    private static final List<SemanticTag> MATERIAL_GROUP =
            List.of(LOG, PLANK, STONE, ORE, BUILDING_BLOCK);

    private final String id;
    private final Set<SemanticKind> kinds;

    SemanticTag(String id, Set<SemanticKind> kinds) {
        this.id = id;
        this.kinds = kinds;
    }

    public String id() {
        return id;
    }

    public Set<SemanticKind> kinds() {
        return kinds;
    }

    public boolean supports(SemanticKind kind) {
        return kinds.contains(kind);
    }

    public static Optional<SemanticTag> byId(String id) {
        return Arrays.stream(values())
                .filter(tag -> tag.id.equals(id))
                .findFirst();
    }

    /** Two material tags disagree when both are present and neither equals the other. */
    public static boolean materialConflict(SemanticTag first, SemanticTag second) {
        return first != second
                && MATERIAL_GROUP.contains(first)
                && MATERIAL_GROUP.contains(second);
    }

    public static boolean isMaterial(SemanticTag tag) {
        return MATERIAL_GROUP.contains(tag);
    }

    /**
     * Directory name used when exporting this category as a {@code #tlmm:} tag.
     *
     * <p>Plural on purpose: tag paths read like sets, and the exported pack only ever
     * creates its own namespace, never rewriting a mod's or vanilla's tag files.
     */
    public String tagPath() {
        return switch (this) {
            case CROP -> "crops";
            case SEED -> "seeds";
            case FOOD -> "foods";
            case RAW_FOOD -> "raw_foods";
            case FUEL -> "fuels";
            case LOG -> "logs";
            case PLANK -> "planks";
            case STONE -> "stones";
            case ORE -> "ores";
            case BUILDING_BLOCK -> "building_blocks";
            case STATION -> "stations";
            case CONTAINER -> "containers";
            case TOOL -> "tools";
            case HOSTILE -> "hostile";
            case LIVESTOCK -> "livestock";
            case BREEDABLE -> "breedable";
            case DECORATIVE -> "decorative";
        };
    }
}

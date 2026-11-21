package com.touhoulittlemaid.mini.fabric.semantic;

import com.touhoulittlemaid.mini.core.semantic.KnowledgeStore;
import com.touhoulittlemaid.mini.core.semantic.SemanticKind;
import com.touhoulittlemaid.mini.core.semantic.SemanticResolution;
import com.touhoulittlemaid.mini.core.semantic.SemanticSource;
import com.touhoulittlemaid.mini.core.semantic.SemanticTag;
import com.touhoulittlemaid.mini.core.semantic.SemanticTags;
import com.touhoulittlemaid.mini.fabric.TlmmFabric;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Process-wide semantic resolver.
 *
 * <p>The knowledge layer is loaded once per server start and can be reloaded on demand.
 * Every consumer — goals, modules, later the client brain — asks this class, so there is
 * exactly one definition of "what is a crop".
 */
public final class SemanticService {

    private static final FabricRealFacts FACTS = new FabricRealFacts();
    private static volatile KnowledgeStore store = new KnowledgeStore();
    private static volatile KnowledgeStore.LoadReport lastReport = KnowledgeStore.LoadReport.empty();

    private SemanticService() {
    }

    public static void load(Path directory) {
        KnowledgeStore fresh = new KnowledgeStore();
        lastReport = fresh.load(directory, FACTS);
        store = fresh;
        if (lastReport.rejected() > 0) {
            TlmmFabric.LOGGER.warn("[knowledge] {} of {} entries were rejected; codes={}",
                    lastReport.rejected(),
                    lastReport.accepted() + lastReport.rejected(), lastReport.codes());
        }
        TlmmFabric.LOGGER.info("[knowledge] loaded {} entries, {} pending from {}",
                lastReport.accepted(), lastReport.pending(), directory);
    }

    public static void reload(Path directory) {
        load(directory);
    }

    public static void reset() {
        store = new KnowledgeStore();
        lastReport = KnowledgeStore.LoadReport.empty();
    }

    public static KnowledgeStore store() {
        return store;
    }

    public static KnowledgeStore.LoadReport lastReport() {
        return lastReport;
    }

    public static SemanticResolution resolve(BlockState state) {
        String id = FabricRealFacts.idOf(state.getBlock());
        if (id.isEmpty()) {
            return SemanticResolution.unknown("", SemanticKind.BLOCK);
        }
        SemanticResolution base = SemanticTags.resolve(id, SemanticKind.BLOCK, FACTS, store);
        return base;
    }

    /** Resolves a placed block together with its block entity (container, station). */
    public static SemanticResolution resolve(BlockState state, BlockEntity entity) {
        SemanticResolution base = resolve(state);
        if (base.isKnown() || entity == null) {
            return base;
        }
        Set<SemanticTag> placed = FACTS.placedBlockTags(entity);
        if (placed.isEmpty()) {
            return base;
        }
        return new SemanticResolution(base.id(), SemanticKind.BLOCK, placed,
                SemanticSource.HEURISTIC, SemanticTags.HEURISTIC_CONFIDENCE);
    }

    public static SemanticResolution resolve(ItemStack stack) {
        if (stack.isEmpty()) {
            return SemanticResolution.unknown("", SemanticKind.ITEM);
        }
        String id = FabricRealFacts.idOf(stack.getItem());
        return SemanticTags.resolve(id, SemanticKind.ITEM, FACTS, store);
    }

    public static SemanticResolution resolve(Entity entity) {
        String id = FabricRealFacts.idOf(entity.getType());
        return SemanticTags.resolve(id, SemanticKind.ENTITY, FACTS, store);
    }

    public static Path knowledgeDirectory(Path gameDirectory) {
        return gameDirectory.resolve("config").resolve("tlmm").resolve("knowledge");
    }

    public static boolean directoryExists(Path directory) {
        return directory != null && Files.isDirectory(directory);
    }
}

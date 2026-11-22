package com.touhoulittlemaid.mini.client.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.touhoulittlemaid.mini.core.semantic.KnowledgeBatch;
import com.touhoulittlemaid.mini.core.semantic.KnowledgeBatchJson;
import com.touhoulittlemaid.mini.core.semantic.KnowledgeCandidate;
import com.touhoulittlemaid.mini.core.semantic.SemanticKind;
import com.touhoulittlemaid.mini.core.semantic.SemanticTag;
import com.touhoulittlemaid.mini.fabric.network.AgentClientPayloads;
import com.touhoulittlemaid.mini.fabric.network.AgentServerPayloads;
import com.touhoulittlemaid.mini.llm.ChatClient;
import com.touhoulittlemaid.mini.llm.ModelConfigStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Asks the client's model to classify a batch of unknown content.
 *
 * <p>This is the step that could not exist before 0.2: the server has no key and no network
 * access to a model, so it sends the batch here. Only ids, kinds, real tags and a display name
 * travel; the answer goes back as JSON the server validates row by row, exactly like an
 * operator typing {@code /tlmm knowledge ingest}.
 */
public final class KnowledgeAnnotator {

    private static final int MAX_PROMPT_ITEMS = 64;

    private KnowledgeAnnotator() {
    }

    /** Runs on the client thread; the model call itself goes to a virtual thread. */
    public static void handle(AgentClientPayloads.KnowledgeRequest payload) {
        List<String> problems = new ArrayList<>();
        KnowledgeBatch parsed = KnowledgeBatchJson.parseRequest(payload.requestJson(), problems);
        if (parsed.items().isEmpty()) {
            return;
        }
        List<KnowledgeCandidate> named = new ArrayList<>();
        for (KnowledgeCandidate candidate : parsed.items()) {
            named.add(candidate.withName(displayName(candidate)));
        }
        KnowledgeBatch batch = new KnowledgeBatch(payload.batchId(), named);

        ModelConfigStore.Loaded loaded = AgentLoop.get().loadConfig();
        if (!loaded.ready()) {
            // Without a key the batch simply stays open; the next scan will ask again.
            return;
        }
        List<ChatClient.Message> messages = List.of(
                ChatClient.Message.system(systemPrompt()),
                ChatClient.Message.user(KnowledgeBatchJson.toRequestJson(batch)));
        Thread.ofVirtual().start(() -> {
            try {
                ChatClient.Reply reply = new ChatClient().complete(loaded.config(),
                        loaded.token(), messages);
                String content = extractObject(reply.content());
                if (content == null) {
                    return;
                }
                Minecraft.getInstance().execute(() -> {
                    if (!ClientPlayNetworking.canSend(
                            AgentServerPayloads.KnowledgeProposal.TYPE)) {
                        return;
                    }
                    ClientPlayNetworking.send(new AgentServerPayloads.KnowledgeProposal(
                            batch.id(), content));
                });
            } catch (RuntimeException exception) {
                // A failed annotation is not a broken game: the batch expires on its own.
                Minecraft.getInstance().execute(() -> AgentLoop.get().onNotice(
                        new AgentClientPayloads.Notice("warn", "annotation_failed",
                                "模型没能完成标注：" + exception.getClass().getSimpleName(),
                                List.of())));
            }
        });
    }

    static String systemPrompt() {
        StringBuilder tags = new StringBuilder();
        for (SemanticTag tag : SemanticTag.values()) {
            if (tags.length() > 0) {
                tags.append(", ");
            }
            tags.append(tag.id()).append('(');
            List<String> kinds = new ArrayList<>();
            for (SemanticKind kind : tag.kinds()) {
                kinds.add(kind.name().toLowerCase(Locale.ROOT));
            }
            tags.append(String.join("/", kinds)).append(')');
        }
        return """
                You classify Minecraft content for a companion mod. You are given ids, their
                kind, the real data pack tags and a display name. Answer with JSON only:
                {"entries":[{"id":"<exactly as given>","tags":["<allowed tag>"],
                  "confidence":0.0,"reason":"<one short line>"}],
                 "unknown":["<ids you cannot classify>"]}

                Allowed tags (kind they may be used on):
                %s

                Rules:
                - at most %d entries; copy the id exactly, never invent one.
                - a tag used on a kind that does not allow it is rejected.
                - material tags (log, plank, stone, ore, building_block) exclude each other.
                - confidence below 0.6 only becomes a suggestion; use it when unsure.
                - hostile is never applied automatically: it always waits for the owner.
                - do not translate, do not add names, do not add fields.
                """.formatted(tags, MAX_PROMPT_ITEMS).strip();
    }

    /** Item or block display name in the player's language; empty when unknown. */
    static String displayName(KnowledgeCandidate candidate) {
        try {
            Identifier id = Identifier.parse(candidate.id());
            return switch (candidate.kind()) {
                case ITEM -> {
                    var item = BuiltInRegistries.ITEM.getValue(id);
                    yield item == null ? "" : new ItemStack(item).getHoverName().getString();
                }
                case BLOCK -> {
                    var block = BuiltInRegistries.BLOCK.getValue(id);
                    yield block == null ? "" : block.getName().getString();
                }
                case ENTITY -> {
                    var type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
                    yield type == null ? "" : type.getDescription().getString();
                }
            };
        } catch (RuntimeException exception) {
            return "";
        }
    }

    /** Finds the first balanced JSON object in a model answer. */
    static String extractObject(String content) {
        if (content == null) {
            return null;
        }
        int start = content.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < content.length(); index++) {
            char character = content.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
                continue;
            }
            if (character == '"') {
                inString = true;
            } else if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return content.substring(start, index + 1);
                }
            }
        }
        return null;
    }

}

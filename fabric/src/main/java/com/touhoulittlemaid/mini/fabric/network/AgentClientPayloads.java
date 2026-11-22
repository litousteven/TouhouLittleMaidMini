package com.touhoulittlemaid.mini.fabric.network;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server → client packets of the client brain.
 *
 * <p>Grouped in one file because they share a rule: every string is length-capped on read, and
 * nothing here carries an inventory, a private data component or another player's chat.
 */
public final class AgentClientPayloads {

    private static final int MAX_JSON = 8 * 1024;
    private static final int MAX_TEXT = 512;

    private AgentClientPayloads() {
    }

    /** The answer to one observation request: a compact JSON snapshot. */
    public record Observation(String requestId, String json) implements CustomPacketPayload {

        public static final Type<Observation> TYPE =
                new Type<>(Identifier.parse("tlmm:agent_observation"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Observation> STREAM_CODEC =
                StreamCodec.of((buffer, payload) -> {
                    buffer.writeUtf(payload.requestId(), MAX_TEXT);
                    buffer.writeUtf(payload.json(), MAX_JSON);
                }, buffer -> new Observation(buffer.readUtf(MAX_TEXT), buffer.readUtf(MAX_JSON)));

        @Override
        public Type<Observation> type() {
            return TYPE;
        }
    }

    /** Progress of the plan a companion is running; sent about every two seconds. */
    public record TaskState(String planId, int step, int steps, String module, int actions,
            int ticks) implements CustomPacketPayload {

        public static final Type<TaskState> TYPE =
                new Type<>(Identifier.parse("tlmm:agent_task_state"));

        public static final StreamCodec<RegistryFriendlyByteBuf, TaskState> STREAM_CODEC =
                StreamCodec.of((buffer, payload) -> {
                    buffer.writeUtf(payload.planId(), MAX_TEXT);
                    buffer.writeVarInt(payload.step());
                    buffer.writeVarInt(payload.steps());
                    buffer.writeUtf(payload.module(), MAX_TEXT);
                    buffer.writeVarInt(payload.actions());
                    buffer.writeVarInt(payload.ticks());
                }, buffer -> new TaskState(buffer.readUtf(MAX_TEXT), buffer.readVarInt(),
                        buffer.readVarInt(), buffer.readUtf(MAX_TEXT), buffer.readVarInt(),
                        buffer.readVarInt()));

        @Override
        public Type<TaskState> type() {
            return TYPE;
        }
    }

    /** Final outcome of a plan, or the reason a submitted plan was refused. */
    public record TaskResult(String planId, boolean ok, String code, String message,
            int completed, int failed, int skipped) implements CustomPacketPayload {

        public static final Type<TaskResult> TYPE =
                new Type<>(Identifier.parse("tlmm:agent_task_result"));

        public static final StreamCodec<RegistryFriendlyByteBuf, TaskResult> STREAM_CODEC =
                StreamCodec.of((buffer, payload) -> {
                    buffer.writeUtf(payload.planId(), MAX_TEXT);
                    buffer.writeBoolean(payload.ok());
                    buffer.writeUtf(payload.code(), MAX_TEXT);
                    buffer.writeUtf(payload.message(), MAX_TEXT);
                    buffer.writeVarInt(payload.completed());
                    buffer.writeVarInt(payload.failed());
                    buffer.writeVarInt(payload.skipped());
                }, buffer -> new TaskResult(buffer.readUtf(MAX_TEXT), buffer.readBoolean(),
                        buffer.readUtf(MAX_TEXT), buffer.readUtf(MAX_TEXT), buffer.readVarInt(),
                        buffer.readVarInt(), buffer.readVarInt()));

        @Override
        public Type<TaskResult> type() {
            return TYPE;
        }
    }

    /** A batch the server wants classified; {@code requestJson} is the batch request shape. */
    public record KnowledgeRequest(String batchId, String requestJson)
            implements CustomPacketPayload {

        public static final Type<KnowledgeRequest> TYPE =
                new Type<>(Identifier.parse("tlmm:agent_knowledge_request"));

        public static final StreamCodec<RegistryFriendlyByteBuf, KnowledgeRequest> STREAM_CODEC =
                StreamCodec.of((buffer, payload) -> {
                    buffer.writeUtf(payload.batchId(), MAX_TEXT);
                    buffer.writeUtf(payload.requestJson(), MAX_JSON);
                }, buffer -> new KnowledgeRequest(buffer.readUtf(MAX_TEXT), buffer.readUtf(MAX_JSON)));

        @Override
        public Type<KnowledgeRequest> type() {
            return TYPE;
        }
    }

    /**
     * The deterministic candidates the director found, sent to the owner's client.
     *
     * <p>{@code auto} tells the client whether a chosen plan may start by itself or has to wait
     * for the player to press confirm. The server re-checks both, so a modified client gains
     * nothing by flipping the flag.
     */
    public record Opportunities(List<Row> rows, String tier, boolean auto)
            implements CustomPacketPayload {

        public static final int MAX_ROWS = 4;

        public record Row(String id, String why, int priority) {
        }

        public static final Type<Opportunities> TYPE =
                new Type<>(Identifier.parse("tlmm:agent_opportunities"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Opportunities> STREAM_CODEC =
                StreamCodec.of((buffer, payload) -> {
                    int size = Math.min(payload.rows().size(), MAX_ROWS);
                    buffer.writeVarInt(size);
                    for (int index = 0; index < size; index++) {
                        Row row = payload.rows().get(index);
                        buffer.writeUtf(row.id(), MAX_TEXT);
                        buffer.writeUtf(row.why(), MAX_TEXT);
                        buffer.writeVarInt(row.priority());
                    }
                    buffer.writeUtf(payload.tier(), 32);
                    buffer.writeBoolean(payload.auto());
                }, buffer -> {
                    int size = Math.min(buffer.readVarInt(), MAX_ROWS);
                    List<Row> rows = new java.util.ArrayList<>(size);
                    for (int index = 0; index < size; index++) {
                        rows.add(new Row(buffer.readUtf(MAX_TEXT), buffer.readUtf(MAX_TEXT),
                                buffer.readVarInt()));
                    }
                    return new Opportunities(rows, buffer.readUtf(32), buffer.readBoolean());
                });

        public Opportunities {
            rows = rows == null ? List.of() : List.copyOf(rows);
            tier = tier == null ? "" : tier;
        }

        @Override
        public Type<Opportunities> type() {
            return TYPE;
        }
    }

    /** A line for the chat panel: a refusal, a warning, a summary. */
    public record Notice(String level, String code, String message,
            List<String> details) implements CustomPacketPayload {

        public static final int MAX_DETAILS = 8;

        public static final Type<Notice> TYPE = new Type<>(Identifier.parse("tlmm:agent_notice"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Notice> STREAM_CODEC =
                StreamCodec.of((buffer, payload) -> {
                    buffer.writeUtf(payload.level(), 32);
                    buffer.writeUtf(payload.code(), MAX_TEXT);
                    buffer.writeUtf(payload.message(), MAX_TEXT);
                    buffer.writeVarInt(payload.details().size());
                    for (String detail : payload.details()) {
                        buffer.writeUtf(detail, MAX_TEXT);
                    }
                }, buffer -> {
                    String level = buffer.readUtf(32);
                    String code = buffer.readUtf(MAX_TEXT);
                    String message = buffer.readUtf(MAX_TEXT);
                    int size = Math.min(buffer.readVarInt(), MAX_DETAILS);
                    List<String> details = new java.util.ArrayList<>(size);
                    for (int index = 0; index < size; index++) {
                        details.add(buffer.readUtf(MAX_TEXT));
                    }
                    return new Notice(level, code, message, details);
                });

        public Notice {
            level = level == null || level.isBlank() ? "info" : level;
            code = code == null ? "" : code;
            message = message == null ? "" : message;
            details = details == null ? List.of() : List.copyOf(details);
        }

        public static Notice info(String message) {
            return new Notice("info", "ok", message, List.of());
        }

        public static Notice warn(String code, String message, List<String> details) {
            return new Notice("warn", code, message, details);
        }

        public static Notice error(String code, String message) {
            return new Notice("error", code, message, List.of());
        }

        @Override
        public Type<Notice> type() {
            return TYPE;
        }
    }
}

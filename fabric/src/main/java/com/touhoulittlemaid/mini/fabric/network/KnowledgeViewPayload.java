package com.touhoulittlemaid.mini.fabric.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server to client: what the knowledge layer currently wants a human to look at.
 *
 * <p>Only ids, tags, confidence and the one-line reason travel — never an inventory, never a
 * model prompt. The client sends its decision back as a command, so approval stays on the
 * same permission-checked path as {@code /tlmm knowledge approve}.
 */
public record KnowledgeViewPayload(List<Row> rows, int accepted, int pending, int batchesToday,
        String batchId, boolean open) implements CustomPacketPayload {

    public static final int MAX_ROWS = 32;

    public record Row(String id, String kind, String tags, double confidence, String reason) {
    }

    public static final Type<KnowledgeViewPayload> TYPE =
            new Type<>(Identifier.parse("tlmm:knowledge_view"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KnowledgeViewPayload> STREAM_CODEC =
            StreamCodec.of(KnowledgeViewPayload::write, KnowledgeViewPayload::read);

    public KnowledgeViewPayload {
        rows = rows == null ? List.of() : List.copyOf(rows);
        batchId = batchId == null ? "" : batchId;
    }

    private static void write(RegistryFriendlyByteBuf buffer, KnowledgeViewPayload payload) {
        buffer.writeVarInt(payload.rows().size());
        for (Row row : payload.rows()) {
            buffer.writeUtf(row.id());
            buffer.writeUtf(row.kind());
            buffer.writeUtf(row.tags());
            buffer.writeDouble(row.confidence());
            buffer.writeUtf(row.reason());
        }
        buffer.writeVarInt(payload.accepted());
        buffer.writeVarInt(payload.pending());
        buffer.writeVarInt(payload.batchesToday());
        buffer.writeUtf(payload.batchId());
        buffer.writeBoolean(payload.open());
    }

    private static KnowledgeViewPayload read(RegistryFriendlyByteBuf buffer) {
        int size = Math.min(buffer.readVarInt(), MAX_ROWS * 4);
        List<Row> rows = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            rows.add(new Row(buffer.readUtf(), buffer.readUtf(), buffer.readUtf(),
                    buffer.readDouble(), buffer.readUtf()));
        }
        return new KnowledgeViewPayload(rows, buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readUtf(), buffer.readBoolean());
    }

    @Override
    public Type<KnowledgeViewPayload> type() {
        return TYPE;
    }
}

package com.touhoulittlemaid.mini.fabric.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server to client: proposals to review and the trust state of adopted templates. */
public record TemplateViewPayload(List<Row> rows, int pending, int templates, int trusted,
        boolean open) implements CustomPacketPayload {

    public static final int MAX_ROWS = 32;

    public record Row(String id, String kind, String name, String state, String detail) {
    }

    public static final Type<TemplateViewPayload> TYPE =
            new Type<>(Identifier.parse("tlmm:template_view"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TemplateViewPayload> STREAM_CODEC =
            StreamCodec.of(TemplateViewPayload::write, TemplateViewPayload::read);

    public TemplateViewPayload {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    private static void write(RegistryFriendlyByteBuf buffer, TemplateViewPayload payload) {
        int size = Math.min(payload.rows().size(), MAX_ROWS);
        buffer.writeVarInt(size);
        for (int index = 0; index < size; index++) {
            Row row = payload.rows().get(index);
            buffer.writeUtf(row.id(), 128);
            buffer.writeUtf(row.kind(), 32);
            buffer.writeUtf(row.name(), 128);
            buffer.writeUtf(row.state(), 32);
            buffer.writeUtf(row.detail(), 256);
        }
        buffer.writeVarInt(payload.pending());
        buffer.writeVarInt(payload.templates());
        buffer.writeVarInt(payload.trusted());
        buffer.writeBoolean(payload.open());
    }

    private static TemplateViewPayload read(RegistryFriendlyByteBuf buffer) {
        int size = Math.min(buffer.readVarInt(), MAX_ROWS);
        List<Row> rows = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            rows.add(new Row(buffer.readUtf(128), buffer.readUtf(32), buffer.readUtf(128),
                    buffer.readUtf(32), buffer.readUtf(256)));
        }
        return new TemplateViewPayload(rows, buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readBoolean());
    }

    @Override
    public Type<TemplateViewPayload> type() {
        return TYPE;
    }
}

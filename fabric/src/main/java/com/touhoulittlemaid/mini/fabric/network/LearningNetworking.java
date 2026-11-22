package com.touhoulittlemaid.mini.fabric.network;

import com.touhoulittlemaid.mini.fabric.learning.TemplateService;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/** Registers and pushes the template review view. */
public final class LearningNetworking {

    private LearningNetworking() {
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(TemplateViewPayload.TYPE,
                TemplateViewPayload.STREAM_CODEC);
    }

    public static void sendView(ServerPlayer player) {
        sendView(player, false);
    }

    public static void sendView(ServerPlayer player, boolean open) {
        if (player == null || !ServerPlayNetworking.canSend(player, TemplateViewPayload.TYPE)) {
            return;
        }
        List<TemplateViewPayload.Row> rows = new ArrayList<>();
        for (TemplateService.TemplateViewRow row : TemplateService.viewRows()) {
            if (rows.size() >= TemplateViewPayload.MAX_ROWS) {
                break;
            }
            rows.add(new TemplateViewPayload.Row(row.id(), row.kind(), row.name(),
                    row.state(), row.detail()));
        }
        ServerPlayNetworking.send(player, new TemplateViewPayload(rows,
                (int) TemplateService.catalog().pendingCount(),
                TemplateService.catalog().templates().size(),
                TemplateService.trustedCount(), open));
    }
}

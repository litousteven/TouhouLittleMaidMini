package com.touhoulittlemaid.mini.fabric.network;

import com.touhoulittlemaid.mini.core.plan.PlanLimits;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client → server packets of the client brain.
 *
 * <p>Nothing is trusted: the plan is re-parsed and re-gated on this side, and a payload that
 * arrives from somebody who does not own the companion is dropped without an answer.
 */
public final class AgentServerPayloads {

    private static final int MAX_TEXT = 512;
    private static final int MAX_PLAN = PlanLimits.MAX_PLAN_BYTES;

    private AgentServerPayloads() {
    }

    /**
     * One plan proposed by the client, already produced by the model.
     *
     * <p>{@code confirmed} is what separates "the owner asked for this in chat" from "the owner
     * pressed confirm on a destructive plan". The server re-gates with the stricter or looser
     * policy accordingly; the flag never skips validation.
     */
    public record SubmitPlan(String requestId, String goal, String planJson, boolean confirmed,
            String source, int tokens) implements CustomPacketPayload {

        public static final Type<SubmitPlan> TYPE =
                new Type<>(Identifier.parse("tlmm:agent_submit_plan"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SubmitPlan> STREAM_CODEC =
                StreamCodec.of((buffer, payload) -> {
                    buffer.writeUtf(payload.requestId(), MAX_TEXT);
                    buffer.writeUtf(payload.goal(), MAX_TEXT);
                    buffer.writeUtf(payload.planJson(), MAX_PLAN);
                    buffer.writeBoolean(payload.confirmed());
                    buffer.writeUtf(payload.source(), 32);
                    buffer.writeVarInt(payload.tokens());
                }, buffer -> new SubmitPlan(buffer.readUtf(MAX_TEXT), buffer.readUtf(MAX_TEXT),
                        buffer.readUtf(MAX_PLAN), buffer.readBoolean(), buffer.readUtf(32),
                        buffer.readVarInt()));

        public SubmitPlan {
            source = source == null || source.isBlank() ? "chat" : source;
            tokens = Math.max(0, tokens);
        }

        @Override
        public Type<SubmitPlan> type() {
            return TYPE;
        }
    }

    /** Ask the companion to stop; an empty id means "whatever it is doing". */
    public record Cancel(String planId) implements CustomPacketPayload {

        public static final Type<Cancel> TYPE = new Type<>(Identifier.parse("tlmm:agent_cancel"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Cancel> STREAM_CODEC =
                StreamCodec.of((buffer, payload) -> buffer.writeUtf(payload.planId(), MAX_TEXT),
                        buffer -> new Cancel(buffer.readUtf(MAX_TEXT)));

        @Override
        public Type<Cancel> type() {
            return TYPE;
        }
    }

    /** Requests a fresh observation; {@code category} is status, inventory or world. */
    public record ObservationRequest(String requestId, String category)
            implements CustomPacketPayload {

        public static final Type<ObservationRequest> TYPE =
                new Type<>(Identifier.parse("tlmm:agent_request_observation"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ObservationRequest> STREAM_CODEC =
                StreamCodec.of((buffer, payload) -> {
                    buffer.writeUtf(payload.requestId(), MAX_TEXT);
                    buffer.writeUtf(payload.category(), 32);
                }, buffer -> new ObservationRequest(buffer.readUtf(MAX_TEXT), buffer.readUtf(32)));

        @Override
        public Type<ObservationRequest> type() {
            return TYPE;
        }
    }

    /** The model's classification answer, forwarded verbatim for server-side validation. */
    public record KnowledgeProposal(String batchId, String json) implements CustomPacketPayload {

        public static final int MAX_JSON = 16 * 1024;

        public static final Type<KnowledgeProposal> TYPE =
                new Type<>(Identifier.parse("tlmm:agent_knowledge_proposal"));

        public static final StreamCodec<RegistryFriendlyByteBuf, KnowledgeProposal> STREAM_CODEC =
                StreamCodec.of((buffer, payload) -> {
                    buffer.writeUtf(payload.batchId(), MAX_TEXT);
                    buffer.writeUtf(payload.json(), MAX_JSON);
                }, buffer -> new KnowledgeProposal(buffer.readUtf(MAX_TEXT),
                        buffer.readUtf(MAX_JSON)));

        @Override
        public Type<KnowledgeProposal> type() {
            return TYPE;
        }
    }
}

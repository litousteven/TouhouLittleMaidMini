package com.touhoulittlemaid.mini.fabric.persistence;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.touhoulittlemaid.mini.core.ActionKind;
import com.touhoulittlemaid.mini.fabric.TlmmFabric;
import com.touhoulittlemaid.mini.mcp.CompanionTokenStore;
import com.touhoulittlemaid.mini.mcp.TokenScope;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Persists only token digests and their scope.
 *
 * <p>Plaintext tokens are never stored: the file keeps SHA-256 digests so a restart can
 * keep existing credentials valid without ever putting a secret on disk.
 */
public final class TokenSavedData extends SavedData {

    private static final Codec<TokenSavedData> CODEC = RecordCodecBuilder.create(instance -> instance
            .group(TokenCodec.CODEC.listOf().optionalFieldOf("tokens", List.of())
                    .forGetter(TokenSavedData::tokens))
            .apply(instance, TokenSavedData::new));

    private static final SavedDataType<TokenSavedData> TYPE = new SavedDataType<>(
            net.minecraft.resources.Identifier.fromNamespaceAndPath(TlmmFabric.MOD_ID, "mcp_tokens"),
            TokenSavedData::new,
            CODEC,
            DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    private List<TokenCodec> tokens;

    public TokenSavedData() {
        this(List.of());
    }

    private TokenSavedData(List<TokenCodec> tokens) {
        this.tokens = new ArrayList<>(tokens);
    }

    public List<TokenCodec> tokens() {
        return List.copyOf(tokens);
    }

    public void replace(List<CompanionTokenStore.Persisted> persisted) {
        this.tokens = persisted.stream().map(TokenCodec::from).toList();
        setDirty();
    }

    public List<CompanionTokenStore.Persisted> toPersisted() {
        return tokens.stream().map(TokenCodec::toPersisted).toList();
    }

    public static TokenSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** Wire representation of one token digest. */
    public record TokenCodec(String id, String digestId, String digest,
            List<String> companionIds, List<String> allowedActions,
            boolean canInteractWithWorld, long expiresAtEpochMilli) {

        static final Codec<TokenCodec> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(TokenCodec::id),
                Codec.STRING.fieldOf("digest_id").forGetter(TokenCodec::digestId),
                Codec.STRING.fieldOf("digest").forGetter(TokenCodec::digest),
                Codec.STRING.listOf().optionalFieldOf("companion_ids", List.of())
                        .forGetter(TokenCodec::companionIds),
                Codec.STRING.listOf().optionalFieldOf("allowed_actions", List.of())
                        .forGetter(TokenCodec::allowedActions),
                Codec.BOOL.optionalFieldOf("can_interact_with_world", true)
                        .forGetter(TokenCodec::canInteractWithWorld),
                Codec.LONG.fieldOf("expires_at").forGetter(TokenCodec::expiresAtEpochMilli)
        ).apply(instance, TokenCodec::new));

        static TokenCodec from(CompanionTokenStore.Persisted persisted) {
            return new TokenCodec(persisted.id(), persisted.digestId(), persisted.digest(),
                    persisted.companionIds().stream().map(UUID::toString).sorted().toList(),
                    persisted.allowedActions().stream().map(ActionKind::id).sorted().toList(),
                    persisted.canInteractWithWorld(), persisted.expiresAtEpochMilli());
        }

        CompanionTokenStore.Persisted toPersisted() {
            Set<UUID> companions = new LinkedHashSet<>();
            for (String value : companionIds) {
                companions.add(UUID.fromString(value));
            }
            Set<ActionKind> actions = EnumSet.noneOf(ActionKind.class);
            for (String value : allowedActions) {
                actions.add(ActionKind.fromId(value));
            }
            TokenScope scope = new TokenScope(actions, canInteractWithWorld);
            return new CompanionTokenStore.Persisted(id, digestId, digest, companions,
                    scope.allowedActions(), scope.canInteractWithWorld(), expiresAtEpochMilli);
        }
    }
}

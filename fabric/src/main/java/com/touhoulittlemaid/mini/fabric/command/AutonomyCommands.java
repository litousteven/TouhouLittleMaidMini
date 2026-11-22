package com.touhoulittlemaid.mini.fabric.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.touhoulittlemaid.mini.core.autonomy.AutonomyTier;
import com.touhoulittlemaid.mini.fabric.autonomy.AutonomyConfig;
import com.touhoulittlemaid.mini.fabric.autonomy.Director;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;

import java.io.IOException;
import java.util.List;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /tlmm autonomy ...}: tier, status and a manual reset.
 *
 * <p>Read-only for everyone, writes for game masters. Changing the tier is a server-wide
 * decision — it applies to every companion, which is why it is one setting and not one per
 * entity.
 */
public final class AutonomyCommands {

    private AutonomyCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("autonomy");
        for (AutonomyTier tier : AutonomyTier.values()) {
            root = root.then(Commands.literal(tier.id())
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(context -> setTier(context.getSource(), tier)));
        }
        return root
                .then(Commands.literal("status")
                        .executes(context -> status(context.getSource())))
                .then(Commands.literal("reset")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> reset(context.getSource())))
                .then(Commands.literal("scan")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("companion", StringArgumentType.word())
                                .executes(context -> scan(context.getSource(),
                                        StringArgumentType.getString(context, "companion")))));
    }

    private static int setTier(CommandSourceStack source, AutonomyTier tier) {
        try {
            AutonomyConfig.set(tier);
        } catch (IOException exception) {
            source.sendFailure(Component.literal("could not write the autonomy config: "
                    + exception.getClass().getSimpleName()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.tlmm.autonomy.set",
                tier.id()), false);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        AutonomyTier tier = Director.tier();
        source.sendSuccess(() -> Component.translatable("command.tlmm.autonomy.status",
                tier.id()), false);
        long now = System.currentTimeMillis();
        if (Director.globallyPaused(now)) {
            source.sendSuccess(() -> Component.literal("ai: "
                    + Director.globalPauseReason() + " ("
                    + (Director.globalPausedUntil() - now) / 1_000 + "s left)"), false);
        }
        List<CompanionEntity> companions = CompanionCommands.ownedCompanions(source);
        for (CompanionEntity companion : companions) {
            Director.State state = Director.state(companion.getUUID());
            source.sendSuccess(() -> Component.literal(companion.companionName() + ": "
                    + state.lastReason() + " (runs/h " + state.budget().runsLastHour(
                            System.currentTimeMillis()) + ")"), false);
        }
        return 1;
    }

    private static int reset(CommandSourceStack source) {
        for (CompanionEntity companion : CompanionCommands.ownedCompanions(source)) {
            Director.state(companion.getUUID()).budget().reset();
        }
        source.sendSuccess(() -> Component.translatable("command.tlmm.autonomy.reset"), false);
        return 1;
    }

    /** Runs one evaluation on demand, so an operator can see the candidates without waiting. */
    private static int scan(CommandSourceStack source, String companionName) {
        for (CompanionEntity companion : CompanionCommands.ownedCompanions(source)) {
            if (!companion.companionName().equalsIgnoreCase(companionName)) {
                continue;
            }
            var found = Director.evaluate(companion, System.currentTimeMillis());
            if (found.isEmpty()) {
                source.sendSuccess(() -> Component.translatable("command.tlmm.autonomy.empty",
                        Director.state(companion.getUUID()).lastReason()), false);
                return 1;
            }
            for (var opportunity : found) {
                source.sendSuccess(() -> Component.literal("- " + opportunity.describe()), false);
            }
            return 1;
        }
        source.sendFailure(Component.translatable("command.tlmm.autonomy.no_companion"));
        return 0;
    }
}

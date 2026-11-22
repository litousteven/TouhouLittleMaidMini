package com.touhoulittlemaid.mini.fabric.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.touhoulittlemaid.mini.fabric.autonomy.Director;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /tlmm ai pause|resume|status}: the operator's hard stop for autonomy.
 *
 * <p>docs/10 §11.4 makes this a first-class requirement: whatever the model, the tier or the
 * director decided, the owner can stop all autonomous action immediately. This pause is
 * global and lives only in memory — a server restart returns to the configured tier.
 */
public final class AiCommands {

    private static final int DEFAULT_MINUTES = 15;

    private AiCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("ai")
                .then(Commands.literal("pause")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> pause(context.getSource(), DEFAULT_MINUTES))
                        .then(Commands.argument("minutes",
                                IntegerArgumentType.integer(1, 240))
                                .executes(context -> pause(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "minutes")))))
                .then(Commands.literal("resume")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> resume(context.getSource())))
                .then(Commands.literal("status")
                        .executes(context -> status(context.getSource())));
    }

    private static int pause(CommandSourceStack source, int minutes) {
        long until = Director.pauseGlobally(System.currentTimeMillis(),
                minutes * 60 * 1_000L, "paused by the owner");
        source.sendSuccess(() -> Component.translatable("command.tlmm.ai.pause", minutes,
                until), false);
        return 1;
    }

    private static int resume(CommandSourceStack source) {
        Director.resumeGlobally();
        source.sendSuccess(() -> Component.translatable("command.tlmm.ai.resume"), false);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        long now = System.currentTimeMillis();
        String state = Director.globallyPaused(now)
                ? Director.globalPauseReason() + " (" + (Director.globalPausedUntil() - now) / 1_000
                        + "s left)"
                : "running";
        source.sendSuccess(() -> Component.translatable("command.tlmm.ai.status", state), false);
        return 1;
    }
}

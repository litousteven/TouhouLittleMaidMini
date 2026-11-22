package com.touhoulittlemaid.mini.fabric.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.touhoulittlemaid.mini.core.learning.PlanTemplate;
import com.touhoulittlemaid.mini.core.learning.TemplateCatalog;
import com.touhoulittlemaid.mini.core.learning.TemplateProposal;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.learning.TemplateService;

import java.util.List;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /tlmm template ...}: the human side of the learning loop.
 *
 * <p>Every mutation is GM-level except {@code confirm}, which any owner may use to authorise
 * one supervised run. Decisions travel through commands rather than packets, so there is
 * exactly one permission-checked path and the review UI can reuse it.
 */
public final class LearningCommands {

    private LearningCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("template")
                .then(Commands.literal("list")
                        .executes(context -> list(context.getSource())))
                .then(Commands.literal("ui")
                        .executes(context -> ui(context.getSource())))
                .then(Commands.literal("propose")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("plan", StringArgumentType.greedyString())
                                .executes(context -> report(context.getSource(),
                                        TemplateService.propose(
                                                StringArgumentType.getString(context, "plan"))))))
                .then(idCommand("adopt", TemplateService::adopt))
                .then(idCommand("reject", TemplateService::reject))
                .then(idCommand("rollback", TemplateService::rollback))
                .then(Commands.literal("confirm")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(context -> report(context.getSource(),
                                        TemplateService.confirm(
                                                StringArgumentType.getString(context, "id"))))))
                .then(Commands.literal("run")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(context -> run(context.getSource(),
                                        StringArgumentType.getString(context, "id")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> idCommand(String name,
            java.util.function.Function<String, TemplateCatalog.Mutation> action) {
        return Commands.literal(name)
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(context -> report(context.getSource(),
                                action.apply(StringArgumentType.getString(context, "id")))));
    }

    private static int list(CommandSourceStack source) {
        TemplateCatalog catalog = TemplateService.catalog();
        source.sendSuccess(() -> Component.translatable("command.tlmm.template.list",
                catalog.pendingCount(), catalog.templates().size(),
                TemplateService.trustedCount()), false);
        for (TemplateProposal proposal : catalog.proposals()) {
            source.sendSuccess(() -> Component.literal("proposal " + proposal.id() + "  "
                    + proposal.status().id() + "  " + proposal.name()), false);
        }
        for (PlanTemplate template : catalog.templates()) {
            source.sendSuccess(() -> Component.literal("template " + template.id() + "  "
                    + template.trust().id() + "  " + template.name()
                    + "  supervised=" + template.supervisedRuns()
                    + "  streak=" + template.consecutiveFailures()), false);
        }
        return 1;
    }

    private static int ui(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("command.tlmm.template.player"));
            return 0;
        }
        TemplateService.watch(player);
        return 1;
    }

    private static int run(CommandSourceStack source, String id) {
        List<CompanionEntity> companions = CompanionCommands.ownedCompanions(source);
        if (companions.isEmpty()) {
            source.sendFailure(Component.translatable("command.tlmm.template.no_companion"));
            return 0;
        }
        return report(source, TemplateService.run(companions.get(0), id));
    }

    private static int report(CommandSourceStack source, TemplateCatalog.Mutation result) {
        if (result.ok()) {
            source.sendSuccess(() -> Component.literal(result.message()), false);
            return 1;
        }
        source.sendFailure(Component.translatable("command.tlmm.template.failed",
                result.code(), result.message()));
        return 0;
    }
}

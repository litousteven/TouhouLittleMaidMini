package com.touhoulittlemaid.mini.fabric.learning;

import com.touhoulittlemaid.mini.core.learning.PlanTemplate;
import com.touhoulittlemaid.mini.core.learning.TemplateCatalog;
import com.touhoulittlemaid.mini.core.learning.TemplateCatalogJson;
import com.touhoulittlemaid.mini.core.learning.TemplateDraft;
import com.touhoulittlemaid.mini.core.learning.TrustLevel;
import com.touhoulittlemaid.mini.core.plan.ParsedPlan;
import com.touhoulittlemaid.mini.core.plan.PlanFormatException;
import com.touhoulittlemaid.mini.core.plan.PlanGate;
import com.touhoulittlemaid.mini.core.plan.PlanJson;
import com.touhoulittlemaid.mini.core.plan.PlanRunner;
import com.touhoulittlemaid.mini.fabric.TlmmFabric;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.network.LearningNetworking;
import com.touhoulittlemaid.mini.fabric.plan.PlanRuntime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;

/**
 * The learning loop on the game side, and the only place that writes template state.
 *
 * <p>A successful plan produces a proposal (data), never an executable template. Adoption,
 * confirmation, rollback and trust changes all go through {@link TemplateCatalog}; the
 * completion listener closes the loop by recording outcomes. A plan started from a template
 * is tagged with its id so a failure can demote exactly that template.
 */
public final class TemplateService {

    public static final String FILE_NAME = "templates.json";

    private static volatile TemplateCatalog catalog = new TemplateCatalog();
    private static volatile Path directory;
    /** plan id -> the plan JSON that produced it, retained until the plan finishes. */
    private static final Map<String, String> PLAN_JSON = new ConcurrentHashMap<>();
    /** plan id -> template id, for outcome bookkeeping. */
    private static final Map<String, String> PLAN_TEMPLATE = new ConcurrentHashMap<>();
    private static final List<ServerPlayer> VIEWERS = new ArrayList<>();

    private TemplateService() {
    }

    public static void register() {
        PlanRuntime.addCompletionListener(TemplateService::onPlanFinished);
    }

    public static TemplateCatalog catalog() {
        return catalog;
    }

    public static void load(Path learningDirectory) {
        // GameTest worlds are disposable and share one run directory. Loading a previous
        // run's proposals here would deduplicate this run's drafts and eventually exhaust
        // the proposal cap, so the disposable server starts from a clean in-memory catalog.
        if (System.getProperty("fabric-api.gametest") != null) {
            catalog = new TemplateCatalog();
            directory = null;
            PLAN_JSON.clear();
            PLAN_TEMPLATE.clear();
            return;
        }
        directory = learningDirectory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(FILE_NAME);
            if (Files.isRegularFile(file)) {
                TemplateCatalogJson.Loaded loaded = TemplateCatalogJson.decode(
                        Files.readString(file, StandardCharsets.UTF_8));
                catalog = loaded.catalog();
                if (!loaded.problems().isEmpty()) {
                    TlmmFabric.LOGGER.warn("[learning] {} bad row(s) ignored in {}",
                            loaded.problems().size(), FILE_NAME);
                }
            } else {
                catalog = new TemplateCatalog();
                save();
            }
        } catch (IOException | RuntimeException exception) {
            catalog = new TemplateCatalog();
            TlmmFabric.LOGGER.warn("[learning] could not read {}: {}", FILE_NAME,
                    exception.getClass().getSimpleName());
        }
    }

    public static void reset() {
        catalog = new TemplateCatalog();
        PLAN_JSON.clear();
        PLAN_TEMPLATE.clear();
        synchronized (VIEWERS) {
            VIEWERS.clear();
        }
        directory = null;
    }

    public static void remember(String planId, String planJson) {
        if (planId != null && planJson != null) {
            PLAN_JSON.put(planId, planJson);
        }
    }

    /** Called by the completion listener; also the single source of automatic proposals. */
    public static void onPlanFinished(UUID companionId, String planId,
            PlanRunner.Summary summary) {
        String templateId = PLAN_TEMPLATE.remove(planId);
        String json = PLAN_JSON.remove(planId);
        long now = System.currentTimeMillis();
        if (templateId != null) {
            TemplateCatalog.Mutation outcome = catalog.recordOutcome(templateId,
                    summary.ok(), now);
            TlmmFabric.LOGGER.info("[learning] {} -> {}", templateId, outcome.message());
            save();
            publish();
            return;
        }
        if (summary.ok() && json != null) {
            TemplateDraft draft = TemplateDraft.fromPlanJson(json, planId);
            if (draft != null) {
                TemplateCatalog.Mutation proposal = catalog.propose(draft, planId, now);
                if (proposal.ok()) {
                    TlmmFabric.LOGGER.info("[learning] {}", proposal.message());
                    save();
                    publish();
                }
            }
        }
    }

    public static TemplateCatalog.Mutation propose(String planJson) {
        long now = System.currentTimeMillis();
        TemplateDraft draft = TemplateDraft.fromPlanJson(planJson, "operator");
        TemplateCatalog.Mutation result = draft == null
                ? TemplateCatalog.Mutation.fail("invalid_draft", "plan is not reusable")
                : catalog.propose(draft, "operator", now);
        if (result.ok()) {
            save();
            publish();
        }
        return result;
    }

    public static TemplateCatalog.Mutation adopt(String id) {
        return mutate(catalog.adopt(id, System.currentTimeMillis()));
    }

    public static TemplateCatalog.Mutation reject(String id) {
        return mutate(catalog.reject(id));
    }

    public static TemplateCatalog.Mutation rollback(String id) {
        return mutate(catalog.rollback(id, System.currentTimeMillis()));
    }

    public static TemplateCatalog.Mutation confirm(String id) {
        return mutate(catalog.confirm(id));
    }

    private static TemplateCatalog.Mutation mutate(TemplateCatalog.Mutation result) {
        if (result.ok()) {
            save();
            publish();
        }
        return result;
    }

    /**
     * Starts one supervised/trusted template run. A supervised template needs a fresh
     * confirmation for every run; a disabled or unreviewed template never runs.
     */
    public static TemplateCatalog.Mutation run(CompanionEntity companion, String templateId) {
        PlanTemplate template = catalog.template(templateId).orElse(null);
        if (template == null) {
            return TemplateCatalog.Mutation.fail("no_template", "no template with that id");
        }
        TemplateCatalog.Mutation allowed = catalog.beginRun(templateId);
        if (!allowed.ok()) {
            return allowed;
        }
        String json = TemplateDraft.planJson(template, Map.of());
        ParsedPlan parsed;
        try {
            parsed = PlanJson.parse(json);
        } catch (PlanFormatException exception) {
            return TemplateCatalog.Mutation.fail("invalid_template", exception.code());
        }
        PlanRuntime.SubmitResult submitted = PlanRuntime.submit(companion, parsed,
                PlanGate.Policy.commandConfirmed(), PlanRuntime.SOURCE_TEMPLATE);
        if (!submitted.ok()) {
            return TemplateCatalog.Mutation.fail(submitted.code(), submitted.message());
        }
        PLAN_TEMPLATE.put(submitted.planId(), templateId);
        save();
        publish();
        return TemplateCatalog.Mutation.ok("started " + submitted.planId() + " from "
                + templateId);
    }

    public static List<TemplateViewRow> viewRows() {
        List<TemplateViewRow> rows = new ArrayList<>();
        catalog.proposals().stream()
                .filter(row -> row.status()
                        == com.touhoulittlemaid.mini.core.learning.TemplateProposal.Status.PENDING)
                .forEach(row -> rows.add(new TemplateViewRow(row.id(), "proposal", row.name(),
                        "pending", row.description())));
        catalog.templates().forEach(row -> rows.add(new TemplateViewRow(row.id(), "template",
                row.name(), row.trust().id(), "supervised=" + row.supervisedRuns()
                        + " streak=" + row.consecutiveFailures())));
        return rows;
    }

    public record TemplateViewRow(String id, String kind, String name, String state,
            String detail) {
    }

    public static int trustedCount() {
        return (int) catalog.templates().stream()
                .filter(row -> row.trust() == TrustLevel.TRUSTED).count();
    }

    public static void publish() {
        synchronized (VIEWERS) {
            for (ServerPlayer player : VIEWERS) {
                LearningNetworking.sendView(player);
            }
        }
    }

    public static void watch(ServerPlayer player) {
        if (player == null) {
            return;
        }
        synchronized (VIEWERS) {
            VIEWERS.removeIf(row -> row.getUUID().equals(player.getUUID()));
            VIEWERS.add(player);
        }
        LearningNetworking.sendView(player, true);
    }

    private static void save() {
        Path file = directory == null ? null : directory.resolve(FILE_NAME);
        if (file == null) {
            return;
        }
        try {
            Path temp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(temp, TemplateCatalogJson.encode(catalog),
                    StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException exception) {
            TlmmFabric.LOGGER.warn("[learning] could not write {}: {}", FILE_NAME,
                    exception.getClass().getSimpleName());
        }
    }
}

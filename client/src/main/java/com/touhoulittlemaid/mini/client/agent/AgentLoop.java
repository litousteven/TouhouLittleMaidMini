package com.touhoulittlemaid.mini.client.agent;

import com.touhoulittlemaid.mini.core.agent.AgentPrompt;
import com.touhoulittlemaid.mini.core.agent.AgentReply;
import com.touhoulittlemaid.mini.core.plan.ModuleRegistry;
import com.touhoulittlemaid.mini.core.plan.ParsedPlan;
import com.touhoulittlemaid.mini.core.plan.PlanFormatException;
import com.touhoulittlemaid.mini.core.plan.PlanGate;
import com.touhoulittlemaid.mini.core.plan.PlanJson;
import com.touhoulittlemaid.mini.fabric.network.AgentClientPayloads;
import com.touhoulittlemaid.mini.fabric.network.AgentServerPayloads;
import com.touhoulittlemaid.mini.fabric.plan.FabricModules;
import com.touhoulittlemaid.mini.llm.ChatClient;
import com.touhoulittlemaid.mini.llm.ModelConfigStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * The client brain: chat in, plan out, progress back.
 *
 * <p>It runs on the client for one reason — the key and the model request must never exist on
 * the server. Everything the model produces is treated as a proposal: it is parsed locally for
 * a fast answer, then re-validated by the server before a single tick is spent.
 *
 * <p>All state lives on the client thread. The model call happens on a virtual thread and comes
 * back through {@link Minecraft#execute}.
 */
public final class AgentLoop {

    public static final int MAX_LINES = 200;
    private static final int OBSERVATION_TIMEOUT_TICKS = 60;

    private static final AgentLoop INSTANCE = new AgentLoop();

    public enum State {
        IDLE,
        WAITING_OBSERVATION,
        THINKING,
        RUNNING
    }

    /** One chat line. {@code role} is user, companion, system or error. */
    public record Line(String role, String text) {
    }

    /** The plan the companion is running, or the one that was just refused. */
    public record Task(String planId, int step, int steps, String module, int actions,
            boolean running, String code, String message) {
    }

    private final List<Line> lines = new ArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger requestIds = new AtomicInteger();
    private final ChatClient client = new ChatClient();

    private State state = State.IDLE;
    private Task task;
    private String pendingRequestId;
    private String pendingMessage;
    private String pendingPlan;
    private String pendingGoal;
    private CompletableFuture<String> observation;
    private int observationDeadline;
    private int ticks;
    private java.util.List<com.touhoulittlemaid.mini.core.autonomy.Opportunity> candidates =
            java.util.List.of();
    private boolean autonomyRound;
    private boolean autonomyAuto;
    private String autonomyTier = "chores";
    private String pendingSource = "chat";
    private int pendingTokens;
    private int lastTokens;

    private AgentLoop() {
    }

    public static AgentLoop get() {
        return INSTANCE;
    }

    // ---- state for the screen ---------------------------------------------------------------

    public List<Line> lines() {
        return List.copyOf(lines);
    }

    public State state() {
        return state;
    }

    public Task task() {
        return task;
    }

    public boolean hasPendingConfirmation() {
        return pendingPlan != null;
    }

    public boolean isBusy() {
        return state == State.WAITING_OBSERVATION || state == State.THINKING;
    }

    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    // ---- input ------------------------------------------------------------------------------

    /** Called by the chat screen when the player sends a line. */
    public void send(String text) {
        String message = text == null ? "" : text.strip();
        if (message.isEmpty()) {
            return;
        }
        addLine("user", message);
        endAutonomyRound();
        if (isBusy()) {
            addLine("system", "还在处理上一条消息，请稍等。");
            return;
        }
        ModelConfigStore.Loaded loaded = loadConfig();
        if (!loaded.ready()) {
            addLine("error", loaded.hasToken()
                    ? "模型配置不完整：请先在 AI 设置里填写 base URL 和模型名。"
                    : "还没有 API Key：请先在 AI 设置里填写。Key 只保存在客户端。");
            return;
        }
        requestObservation(message);
    }

    /** The player confirmed a destructive plan. */
    public void confirmPending() {
        if (pendingPlan == null) {
            return;
        }
        String plan = pendingPlan;
        String goal = pendingGoal;
        // Capture the round's provenance before clearing it: a confirmed "suggest" plan is
        // still an autonomy plan and must carry the opportunity id and its token count.
        String source = pendingSource;
        int tokens = pendingTokens;
        pendingPlan = null;
        pendingGoal = null;
        pendingSource = "chat";
        pendingTokens = 0;
        addLine("system", "已确认，开始执行。");
        submitPlan(plan, goal, true, source, tokens);
    }

    public void dropPending() {
        if (pendingPlan == null) {
            return;
        }
        pendingPlan = null;
        pendingGoal = null;
        pendingSource = "chat";
        pendingTokens = 0;
        endAutonomyRound();
        addLine("system", "已取消这次计划。");
        notifyListeners();
    }

    /** Stops whatever is running, if anything. */
    public void cancelCurrent() {
        Task current = task;
        if (current == null || !current.running()) {
            addLine("system", "当前没有正在执行的任务。");
            return;
        }
        ClientPlayNetworking.send(new AgentServerPayloads.Cancel(current.planId()));
        task = new Task(current.planId(), current.step(), current.steps(), current.module(),
                current.actions(), false, "cancelled", "已请求停止");
        addLine("system", "已请求停止。");
        notifyListeners();
    }

    // ---- packet handlers --------------------------------------------------------------------

    public void onObservation(AgentClientPayloads.Observation payload) {
        CompletableFuture<String> waiting = observation;
        if (waiting == null || pendingRequestId == null
                || !pendingRequestId.equals(payload.requestId())) {
            return;
        }
        observation = null;
        String message = pendingMessage;
        pendingMessage = null;
        String json = payload.json();
        if (json != null && json.contains("\"error\":\"rate_limited\"")) {
            addLine("error", "请求太快了，等一会儿再试。");
            state = State.IDLE;
            notifyListeners();
            return;
        }
        if (json == null || json.isBlank() || "{}".equals(json.strip())) {
            addLine("error", "没有找到你的同伴，先把她叫到身边。");
            state = State.IDLE;
            notifyListeners();
            return;
        }
        waiting.complete(json);
        think(message, json);
    }

    /**
     * The director found candidates. Autonomy never interrupts a chat answer in progress: a
     * player who is being answered is more important than a chore.
     */
    public void onOpportunities(AgentClientPayloads.Opportunities payload) {
        if (payload.rows().isEmpty() || state != State.IDLE || planRunning()
                || !loadConfig().ready()) {
            return;
        }
        candidates = payload.rows().stream()
                .map(row -> com.touhoulittlemaid.mini.core.autonomy.Opportunity.of(row.id(),
                        row.why(), row.priority()))
                .toList();
        autonomyRound = true;
        autonomyAuto = payload.auto();
        autonomyTier = payload.tier().isBlank() ? "chores" : payload.tier();
        requestObservation(null);
    }

    /** True while a plan is running; checked through the task snapshot the server sends. */
    private boolean planRunning() {
        Task current = task;
        return current != null && current.running();
    }

    /** Clears the round on every exit path, so a failed model call cannot leave it stuck. */
    private void endAutonomyRound() {
        autonomyRound = false;
        candidates = java.util.List.of();
    }

    public void onTaskState(AgentClientPayloads.TaskState payload) {
        task = new Task(payload.planId(), payload.step(), payload.steps(), payload.module(),
                payload.actions(), true, "", "");
        state = State.RUNNING;
        notifyListeners();
    }

    public void onTaskResult(AgentClientPayloads.TaskResult payload) {
        if (!payload.ok() && payload.planId() != null
                && payload.planId().startsWith("req-")) {
            // A refusal is correlated with the request, not with a plan.
            addLine("error", describe(payload.code()) + "： " + payload.message());
            state = State.IDLE;
            notifyListeners();
            return;
        }
        task = new Task(payload.planId(), 0, 0, "", 0, false, payload.code(),
                payload.message());
        state = State.IDLE;
        if (payload.ok()) {
            addLine("companion", "做完了：" + payload.completed() + " 步成功"
                    + (payload.failed() > 0 ? "，" + payload.failed() + " 步失败" : "")
                    + "。");
        } else {
            addLine("error", "没做成（" + describe(payload.code()) + "）：" + payload.message());
        }
        notifyListeners();
    }

    public void onNotice(AgentClientPayloads.Notice payload) {
        String text = payload.message();
        if (text == null || text.isBlank()) {
            return;
        }
        addLine("error".equals(payload.level()) ? "error" : "system", text);
    }

    /** Called once per client tick; only the observation timeout needs it. */
    public void tick() {
        ticks++;
        if (state == State.WAITING_OBSERVATION && ticks > observationDeadline) {
            observation = null;
            pendingMessage = null;
            state = State.IDLE;
            endAutonomyRound();
            addLine("error", "服务器没有回应观察请求，稍后再试。");
        }
    }

    // ---- pipeline ---------------------------------------------------------------------------

    private void requestObservation(String message) {
        String requestId = "req-" + requestIds.incrementAndGet();
        pendingRequestId = requestId;
        pendingMessage = message;
        observation = new CompletableFuture<>();
        observationDeadline = ticks + OBSERVATION_TIMEOUT_TICKS;
        state = State.WAITING_OBSERVATION;
        ClientPlayNetworking.send(
                new AgentServerPayloads.ObservationRequest(requestId, "status"));
        notifyListeners();
    }

    private void think(String message, String observationJson) {
        state = State.THINKING;
        notifyListeners();
        ModelConfigStore.Loaded loaded = loadConfig();
        if (!loaded.ready()) {
            state = State.IDLE;
            endAutonomyRound();
            addLine("error", "模型配置不完整，请先在 AI 设置里补全。");
            return;
        }
        ModuleRegistry registry = FabricModules.registry();
        String userMessage = autonomyRound
                ? AgentPrompt.autonomyUser(observationJson, candidates, autonomyAuto)
                : AgentPrompt.user(observationJson, message);
        List<ChatClient.Message> messages = List.of(
                ChatClient.Message.system(
                        AgentPrompt.system(companionName(observationJson), registry)),
                ChatClient.Message.user(userMessage));
        completeAsync(loaded, messages, registry, 0);
    }

    /**
     * One model call, plus at most one follow-up for {@code describe}.
     *
     * <p>The depth cap matters: a model that keeps asking for descriptions would otherwise
     * keep the player waiting and keep spending tokens.
     */
    private void completeAsync(ModelConfigStore.Loaded loaded, List<ChatClient.Message> messages,
            ModuleRegistry registry, int depth) {
        Thread.ofVirtual().start(() -> {
            try {
                ChatClient.Reply reply = client.complete(loaded.config(), loaded.token(),
                        messages);
                int tokens = reply.usage() == null ? 0 : reply.usage().total();
                onClientThread(() -> {
                    lastTokens = tokens;
                    handleReply(reply.content(), loaded, messages, registry, depth);
                });
            } catch (ChatClient.ModelException exception) {
                onClientThread(() -> {
                    state = State.IDLE;
                    endAutonomyRound();
                    addLine("error", "模型调用失败（" + exception.code() + "）："
                            + explain(exception.code()));
                });
            } catch (RuntimeException exception) {
                onClientThread(() -> {
                    state = State.IDLE;
                    endAutonomyRound();
                    addLine("error", "模型调用失败：" + exception.getClass().getSimpleName());
                });
            }
        });
    }

    private void handleReply(String content, ModelConfigStore.Loaded loaded,
            List<ChatClient.Message> messages, ModuleRegistry registry, int depth) {
        String describe = AgentReply.describeRequest(content);
        if (describe != null && depth < 1) {
            var module = registry.find(describe);
            if (module.isEmpty()) {
                state = State.IDLE;
                endAutonomyRound();
                addLine("error", "模型问了一个不存在的模块：" + describe);
                return;
            }
            List<ChatClient.Message> followUp = new ArrayList<>(messages);
            followUp.add(ChatClient.Message.assistant(content));
            followUp.add(ChatClient.Message.user(AgentPrompt.describe(module.get().spec(),
                    module.get().risk())));
            completeAsync(loaded, List.copyOf(followUp), registry, depth + 1);
            return;
        }

        AgentReply reply = AgentReply.parse(content);
        if (!reply.ok()) {
            state = State.IDLE;
            endAutonomyRound();
            addLine("error", "模型回答看不懂（" + reply.code() + "）：" + reply.message());
            return;
        }
        if (!reply.say().isBlank()) {
            addLine("companion", reply.say());
        }
        if (autonomyRound) {
            handleAutonomyChoice(reply, registry);
            return;
        }
        if (!reply.hasPlan()) {
            state = State.IDLE;
            notifyListeners();
            return;
        }
        ParsedPlan parsed;
        try {
            parsed = PlanJson.parse(reply.planJson());
        } catch (PlanFormatException exception) {
            state = State.IDLE;
            addLine("error", "计划格式不对（" + exception.code() + "）："
                    + exception.getMessage());
            return;
        }
        PlanGate.Result check = new PlanGate(registry, PlanGate.Policy.command()).check(parsed);
        if (!check.ok()) {
            if ("confirmation_required".equals(check.code())) {
                pendingPlan = reply.planJson();
                pendingGoal = parsed.plan().goal();
                state = State.IDLE;
                addLine("system", "这个计划会破坏方块，需要你确认：" + check.message());
                notifyListeners();
                return;
            }
            state = State.IDLE;
            addLine("error", "计划被本地检查拒绝（" + describe(check.code()) + "）："
                    + check.message());
            return;
        }
        submitPlan(reply.planJson(), parsed.plan().goal(), false,
                autonomyRound ? "autonomy" : "chat", lastTokens);
    }

    /** Handles the model's answer to an autonomy round; it never falls through to chat. */
    private void handleAutonomyChoice(AgentReply reply, ModuleRegistry registry) {
        String chosen = reply.choice() == null ? "" : reply.choice().strip();
        boolean offered = candidates.stream().anyMatch(row -> row.id().equals(chosen));
        if (chosen.isEmpty() || "none".equalsIgnoreCase(chosen)) {
            endAutonomyRound();
            state = State.IDLE;
            addLine("system", "她看了看，觉得现在不用做什么。");
            notifyListeners();
            return;
        }
        if (!offered) {
            endAutonomyRound();
            state = State.IDLE;
            addLine("error", "模型选了一个不在候选里的目标：" + chosen);
            notifyListeners();
            return;
        }
        if (!reply.hasPlan()) {
            endAutonomyRound();
            state = State.IDLE;
            addLine("system", "她选了 " + chosen + "，但没有给出计划。");
            notifyListeners();
            return;
        }
        if (!autonomyAuto) {
            // Suggest tier: the plan is good, the decision is the player's.
            pendingPlan = reply.planJson();
            pendingGoal = chosen;
            pendingSource = "autonomy";
            pendingTokens = lastTokens;
            endAutonomyRound();
            state = State.IDLE;
            addLine("system", "要不要我去做这件事？");
            notifyListeners();
            return;
        }
        ParsedPlan parsed;
        try {
            parsed = PlanJson.parse(reply.planJson());
        } catch (PlanFormatException exception) {
            endAutonomyRound();
            state = State.IDLE;
            addLine("error", "自主计划的格式不对（" + exception.code() + "）");
            return;
        }
        PlanGate.Result check = new PlanGate(registry,
                com.touhoulittlemaid.mini.core.autonomy.AutonomyTier.fromId(autonomyTier)
                        .policy()).check(parsed);
        if (!check.ok()) {
            endAutonomyRound();
            state = State.IDLE;
            addLine("error", "自主计划被本地检查拒绝（" + describe(check.code()) + "）："
                    + check.message());
            return;
        }
        autonomyRound = false;
        candidates = java.util.List.of();
        submitPlan(reply.planJson(), chosen, false, "autonomy", lastTokens);
    }

    private void submitPlan(String planJson, String goal, boolean confirmed, String source,
            int tokens) {
        if (!ClientPlayNetworking.canSend(AgentServerPayloads.SubmitPlan.TYPE)) {
            state = State.IDLE;
            addLine("error", "服务器没有安装这个模组，无法执行计划。");
            return;
        }
        pendingRequestId = "req-" + requestIds.incrementAndGet();
        ClientPlayNetworking.send(new AgentServerPayloads.SubmitPlan(pendingRequestId,
                goal == null ? "" : goal, planJson, confirmed, source, tokens));
        state = State.RUNNING;
        notifyListeners();
    }

    // ---- helpers ----------------------------------------------------------------------------

    public ModelConfigStore.Loaded loadConfig() {
        Minecraft minecraft = Minecraft.getInstance();
        Path directory = minecraft.gameDirectory.toPath();
        return ModelConfigStore.load(directory);
    }

    /** The name the server put in the observation; falls back to a neutral one. */
    static String companionName(String observationJson) {
        try {
            var root = com.google.gson.JsonParser.parseString(observationJson);
            if (root.isJsonObject()) {
                var companion = root.getAsJsonObject().get("companion");
                if (companion != null && companion.isJsonObject()) {
                    var name = companion.getAsJsonObject().get("name");
                    if (name != null && !name.isJsonNull() && !name.getAsString().isBlank()) {
                        return name.getAsString();
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // A malformed observation still produces a usable prompt.
        }
        return "the companion";
    }

    private static void onClientThread(Runnable runnable) {
        Minecraft.getInstance().execute(runnable);
    }

    private void addLine(String role, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        lines.add(new Line(role, text));
        while (lines.size() > MAX_LINES) {
            lines.remove(0);
        }
        notifyListeners();
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException exception) {
                // A broken screen must not stop the loop.
            }
        }
    }

    /** Player-facing wording for a failure code; the code itself stays in the log. */
    public static String describe(String code) {
        if (code == null) {
            return "未知原因";
        }
        return switch (code) {
            case "no_companion" -> "没有同伴";
            case "rate_limited" -> "请求太快";
            case "busy" -> "正在忙";
            case "confirmation_required" -> "需要确认";
            case "invalid_plan" -> "计划无效";
            case "invalid_params" -> "参数不对";
            case "unknown_module" -> "没有这个动作";
            case "plan_too_large" -> "计划太大";
            case "too_many_steps" -> "步骤太多";
            case "budget_exceeded" -> "超出预算";
            case "tool_not_in_inventory" -> "缺少工具";
            case "wrong_tool" -> "工具等级不够";
            case "tool_lost" -> "工具被拿走了";
            case "missing_item" -> "缺少材料";
            case "inventory_full" -> "背包满了";
            case "no_recipe" -> "没有这个配方";
            case "not_harvestable" -> "没有可收的东西";
            case "unknown_category" -> "不认识这个世界里的东西";
            case "unreachable" -> "走不过去";
            case "cancelled" -> "已取消";
            case "stale_batch" -> "标注批次已过期";
            default -> code;
        };
    }

    private static String explain(String code) {
        if (code == null) {
            return "未知错误";
        }
        if ("missing_token".equals(code)) {
            return "没有 Key，请打开 AI 设置填写。";
        }
        if ("missing_config".equals(code)) {
            return "base URL 或模型名是空的。";
        }
        if (code.startsWith("http_401") || code.startsWith("http_403")) {
            return "Key 无效或没有权限。";
        }
        if (code.startsWith("http_429")) {
            return "被限流了，稍后再试。";
        }
        if (code.startsWith("http_")) {
            return "服务端返回错误。";
        }
        if ("empty_content".equals(code)) {
            return "模型返回了空内容。";
        }
        return code;
    }

    /** Kept for callers that only need to know whether a plan can even be attempted. */
    public static boolean canSendPlan() {
        return ClientPlayNetworking.canSend(AgentServerPayloads.SubmitPlan.TYPE);
    }
}

package com.touhoulittlemaid.mini.core.agent;

import com.touhoulittlemaid.mini.core.plan.ActionModule;
import com.touhoulittlemaid.mini.core.plan.ModuleRegistry;
import com.touhoulittlemaid.mini.core.plan.ModuleSpec;
import com.touhoulittlemaid.mini.core.plan.ParamSpec;
import com.touhoulittlemaid.mini.core.plan.PlanLimits;
import com.touhoulittlemaid.mini.core.plan.RiskClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the two messages the client brain sends to the model.
 *
 * <p>The catalogue is generated from the same {@link ModuleRegistry} the server validates
 * against, so the model can never be told about a module that does not exist. Each module
 * gets one line; the full parameter list is only sent when the model asks for it with
 * {@code {"describe":"<id>"}}, which keeps a normal request small.
 */
public final class AgentPrompt {

    /**
     * The catalogue is one line per module and capped in total length.
     *
     * <p>The design asked for "12 lines". Every module gets a line instead, because hiding
     * three of fifteen modules made the model unable to ask for them — it cannot request a
     * description of an id it has never seen. The budget is enforced by characters, which is
     * what actually bounds the request.
     */
    public static final int MAX_CATALOG_CHARS = 1800;

    private AgentPrompt() {
    }

    public static String system(String companionName, ModuleRegistry registry) {
        String name = companionName == null || companionName.isBlank() ? "the companion" : companionName;
        return """
                You are %s, a companion living in a Minecraft world. Your body is the entity the
                owner sees; you act only through plans. You cannot run commands, code, or files.

                Answer with ONE JSON object and nothing else:
                {"say":"<what you tell the owner>","plan":{"version":%d,"goal":"<short>","steps":[{"module":"<id>","params":{...}}]}}
                Talking is enough: {"say":"...","plan":null}
                Need a module's full parameters: {"describe":"<module id>"}

                Rules:
                - say: at most two sentences, in the owner's language.
                - module must be an id from the catalogue; params must use the listed names.
                - at most %d steps, and only what can finish in about a minute.
                - never invent module ids, and never emit shell commands, code, URLs or paths.
                - if the observation already satisfies the request, use plan:null and say so.
                - a module marked (destructive) needs the owner to confirm it in the chat.

                Modules:
                %s

                Example:
                {"say":"好，我去把那片小麦收了。","plan":{"version":%d,"goal":"harvest wheat","steps":[{"module":"harvest_area","params":{"radius":8,"replant":true}}]}}
                """.formatted(name, PlanLimits.VERSION, PlanLimits.MAX_STEPS, catalog(registry),
                PlanLimits.VERSION).strip();
    }

    /** One line per module: id, summary, and the parameter names with defaults. */
    public static String catalog(ModuleRegistry registry) {
        StringBuilder out = new StringBuilder();
        for (ActionModule module : registry.all()) {
            String line = line(module.spec(), module.risk());
            if (out.length() + line.length() + 1 > MAX_CATALOG_CHARS) {
                out.append("- (catalogue truncated; ask for a module by id)\n");
                break;
            }
            out.append(line).append('\n');
        }
        return out.toString().stripTrailing();
    }

    private static String line(ModuleSpec spec, RiskClass risk) {
        StringBuilder out = new StringBuilder("- ").append(spec.id());
        if (risk == RiskClass.DESTROY) {
            out.append(" (destructive)");
        }
        if (!spec.summary().isBlank()) {
            out.append(": ").append(spec.summary());
        }
        List<String> params = new ArrayList<>();
        for (ParamSpec param : spec.params()) {
            params.add(hint(param));
        }
        if (!params.isEmpty()) {
            out.append(" [").append(String.join(", ", params)).append(']');
        }
        return out.toString();
    }

    private static String hint(ParamSpec param) {
        String type = switch (param.type()) {
            case STRING -> "str";
            case STRING_LIST -> "str[]";
            case INT -> "int";
            case DOUBLE -> "num";
            case BOOLEAN -> "bool";
        };
        StringBuilder out = new StringBuilder(param.name()).append(':').append(type);
        if (param.defaultValue() != null) {
            out.append('=').append(String.valueOf(param.defaultValue()));
        } else if (param.required()) {
            out.append('!');
        }
        return out.toString();
    }

    /** The answer to {@code {"describe":"<id>"}}; the model is asked to plan right after. */
    public static String describe(ModuleSpec spec, RiskClass risk) {
        StringBuilder out = new StringBuilder();
        out.append("Module ").append(spec.id()).append(": ").append(spec.summary());
        if (risk == RiskClass.DESTROY) {
            out.append(" (destructive: the owner must confirm)");
        }
        out.append("\nParameters (\"!\" means required):\n");
        if (spec.params().isEmpty()) {
            out.append("- none\n");
        }
        for (ParamSpec param : spec.params()) {
            out.append("- ").append(param.name()).append(": ")
                    .append(param.type().name().toLowerCase(Locale.ROOT));
            if (param.required()) {
                out.append(" required");
            }
            if (param.min() != null || param.max() != null) {
                out.append(" range=").append(format(param.min())).append("..")
                        .append(format(param.max()));
            }
            if (!param.allowed().isEmpty()) {
                out.append(" oneOf=").append(param.allowed());
            }
            if (param.defaultValue() != null) {
                out.append(" default=").append(param.defaultValue());
            }
            if (!param.description().isBlank()) {
                out.append(" — ").append(param.description());
            }
            out.append('\n');
        }
        out.append("Answer with the plan JSON now, or with plan:null if no plan is needed.");
        return out.toString();
    }

    private static String format(Double value) {
        if (value == null) {
            return "?";
        }
        return value == Math.rint(value) ? Long.toString(value.longValue())
                : String.format(Locale.ROOT, "%.2f", value);
    }

    public static String user(String observationJson, String playerMessage) {
        return """
                Observation (server truth, read-only):
                %s

                Owner says:
                %s
                """.formatted(observationJson == null ? "{}" : observationJson,
                playerMessage == null ? "" : playerMessage).strip();
    }
}

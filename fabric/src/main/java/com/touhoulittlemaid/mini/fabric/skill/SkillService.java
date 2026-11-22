package com.touhoulittlemaid.mini.fabric.skill;

import com.google.gson.JsonObject;
import com.touhoulittlemaid.mini.core.skill.SkillIndex;
import com.touhoulittlemaid.mini.core.skill.SkillLoader;
import com.touhoulittlemaid.mini.fabric.TlmmFabric;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Game-side owner of the three-level Skill loader.
 *
 * <p>Built-in skills are materialised into {@code skills/builtin/} on first run so the same
 * {@link SkillLoader} works for both sources, and a player file directly in
 * {@code skills/} overrides a built-in with the same name. Only the index is ever attached
 * to an observation; bodies and attachments are pulled on demand through a categorised
 * observation request, so the model pays for what it actually reads.
 */
public final class SkillService {

    private static final String BUILTIN_EXAMPLE = """
            ---
            name: harvest-and-store
            description: Wheat is mature and the storage chest is nearby; harvest and deposit.
            ---
            ## Steps
            1. harvest_area (radius, replant)
            2. deposit_items (item, count)
            ## Parameters
            - radius: default 8, max 32
            """;

    private static volatile SkillLoader loader = new SkillLoader(null, null);

    private SkillService() {
    }

    public static void load(Path skillsDirectory) {
        Path root = skillsDirectory.toAbsolutePath().normalize();
        Path builtin = root.resolve("builtin");
        try {
            Files.createDirectories(builtin);
            Path example = builtin.resolve("harvest-and-store.md");
            if (Files.notExists(example)) {
                Files.writeString(example, BUILTIN_EXAMPLE, StandardCharsets.UTF_8);
            }
            loader = new SkillLoader(builtin, root);
        } catch (IOException | RuntimeException exception) {
            TlmmFabric.LOGGER.warn("[skills] could not prepare {}: {}", root,
                    exception.getClass().getSimpleName());
            loader = new SkillLoader(null, root);
        }
    }

    public static void reset() {
        loader = new SkillLoader(null, null);
    }

    public static SkillIndex index() {
        return loader.index();
    }

    /**
     * Level 1 already lives in the observation. This answers level 2
     * ({@code skill:<name>}) and level 3 ({@code skill_file:<name>:<relative>}) requests.
     */
    public static String observe(String category) {
        if (category == null) {
            return "{\"error\":\"unknown_category\"}";
        }
        if (category.startsWith("skill_file:")) {
            String rest = category.substring("skill_file:".length());
            int colon = rest.indexOf(':');
            if (colon <= 0) {
                return "{\"error\":\"invalid_request\"}";
            }
            return wrap("file", rest.substring(colon + 1),
                    loader.attachment(rest.substring(0, colon), rest.substring(colon + 1)));
        }
        if (category.startsWith("skill:")) {
            String name = category.substring("skill:".length());
            return wrap("body", name, loader.body(name));
        }
        return "{\"error\":\"unknown_category\"}";
    }

    private static String wrap(String kind, String name, SkillLoader.Result result) {
        JsonObject root = new JsonObject();
        if (!result.ok()) {
            root.addProperty("error", result.code());
            return root.toString();
        }
        root.addProperty("kind", kind);
        root.addProperty("name", name);
        root.addProperty("text", result.text());
        return root.toString();
    }
}

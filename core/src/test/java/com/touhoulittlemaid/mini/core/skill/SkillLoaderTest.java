package com.touhoulittlemaid.mini.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillLoaderTest {

    @TempDir
    Path temp;

    private static void write(Path path, String text) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, text, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String skill(String name, String description) {
        return """
                ---
                name: %s
                description: %s
                ---
                ## 步骤
                1. harvest_area
                2. deposit_items
                """.formatted(name, description);
    }

    @Test
    void theIndexIsNamesAndDescriptionsOnly() {
        Path builtin = temp.resolve("builtin");
        write(builtin.resolve("farming.md"), skill("farming", "收菜流程"));
        SkillIndex index = new SkillLoader(builtin, null).index();
        assertEquals(1, index.entries().size());
        assertEquals("farming", index.entries().get(0).name());
        assertEquals("收菜流程", index.entries().get(0).description());
        String lines = index.promptLines(8);
        assertTrue(lines.contains("farming"), lines);
        assertFalse(lines.contains("harvest_area"), "level 1 must not leak the body");
    }

    @Test
    void aUserSkillOverridesTheBuiltinWithTheSameName() {
        Path builtin = temp.resolve("builtin");
        Path user = temp.resolve("user");
        write(builtin.resolve("farming.md"), skill("farming", "内置"));
        write(user.resolve("farming.md"), skill("farming", "玩家覆盖"));
        SkillIndex index = new SkillLoader(builtin, user).index();
        assertEquals(1, index.entries().size());
        assertEquals("玩家覆盖", index.entries().get(0).description());
    }

    @Test
    void levelTwoAndThreeReadOnlyFromInsideTheSkillDirectory() {
        Path root = temp.resolve("skills");
        write(root.resolve("farming.md"), skill("farming", "收菜"));
        write(root.resolve("farming").resolve("notes.txt"), "extra detail");
        write(temp.resolve("secret.txt"), "should never be reachable");
        SkillLoader loader = new SkillLoader(root, null);

        assertTrue(loader.body("farming").ok());
        assertTrue(loader.body("farming").text().contains("harvest_area"));
        assertEquals("extra detail", loader.attachment("farming", "notes.txt").text());
        assertFalse(loader.attachment("farming", "../secret.txt").ok());
        assertEquals("invalid_path", loader.attachment("farming", "../secret.txt").code());
        assertFalse(loader.attachment("farming", "sub/../../secret.txt").ok());
        assertFalse(loader.attachment("farming", "/etc/passwd").ok());
        assertFalse(loader.body("../secret").ok());
        assertFalse(loader.attachment("farming", "notes.exe").ok());
    }

    @Test
    void oversizedFilesAreRefused() {
        Path root = temp.resolve("big");
        write(root.resolve("farming.md"), skill("farming", "收菜"));
        write(root.resolve("farming").resolve("huge.txt"), "x".repeat(
                SkillLoader.MAX_BYTES + 1));
        SkillLoader loader = new SkillLoader(root, null);
        assertEquals("too_large", loader.attachment("farming", "huge.txt").code());
    }
}

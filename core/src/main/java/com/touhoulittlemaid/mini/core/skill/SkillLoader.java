package com.touhoulittlemaid.mini.core.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Three-level Skill loading with a hard directory boundary.
 *
 * <p>Level 1 is the index (name + description only). Level 2 is one skill's Markdown body.
 * Level 3 is an attachment inside that skill's own directory. Nothing outside
 * {@code <root>/<skill>/} can ever be read, and every read is capped — a skill is data for a
 * prompt, not a way to pull arbitrary files into the model context. User skills in
 * {@code user} override built-in ones with the same name.
 */
public final class SkillLoader {

    public static final int MAX_BYTES = 256 * 1024;
    public static final int MAX_SKILLS = 64;
    private static final Set<String> ATTACHMENT_EXTENSIONS = Set.of(
            ".md", ".txt", ".json", ".csv", ".properties");

    public record Result(boolean ok, String code, String text) {

        public static Result ok(String text) {
            return new Result(true, "ok", text);
        }

        public static Result fail(String code) {
            return new Result(false, code, "");
        }
    }

    private final Path builtin;
    private final Path user;

    public SkillLoader(Path builtin, Path user) {
        this.builtin = builtin == null ? null : builtin.toAbsolutePath().normalize();
        this.user = user == null ? null : user.toAbsolutePath().normalize();
    }

    /** Level 1: name + description of every readable skill, user wins on name conflicts. */
    public SkillIndex index() {
        Map<String, SkillIndex.Entry> found = new LinkedHashMap<>();
        collect(builtin, found);
        collect(user, found);
        List<SkillIndex.Entry> entries = new ArrayList<>(found.values());
        if (entries.size() > MAX_SKILLS) {
            entries = new ArrayList<>(entries.subList(0, MAX_SKILLS));
        }
        return new SkillIndex(entries);
    }

    private static void collect(Path root, Map<String, SkillIndex.Entry> found) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted().limit(MAX_SKILLS).forEach(path -> {
                        String name = stem(path);
                        if (name.isEmpty()) {
                            return;
                        }
                        Result body = read(path);
                        if (!body.ok()) {
                            return;
                        }
                        found.put(name, new SkillIndex.Entry(name,
                                frontmatter(body.text()).getOrDefault("description", "")));
                    });
        } catch (IOException | RuntimeException exception) {
            // A broken directory is an empty index, never a crash in the observation path.
        }
    }

    /** Level 2: the full Markdown of one skill. */
    public Result body(String name) {
        Path file = bodyFile(name);
        if (file == null) {
            return Result.fail("invalid_name");
        }
        return read(file);
    }

    /** Level 3: one attachment from inside that skill's own directory. */
    public Result attachment(String name, String relative) {
        Path file = bodyFile(name);
        if (file == null) {
            return Result.fail("invalid_name");
        }
        Path skillDirectory = file.getParent().resolve(stem(file)).normalize();
        if (relative == null || relative.isBlank() || relative.startsWith("/")
                || relative.contains("\\") || relative.contains("..")) {
            return Result.fail("invalid_path");
        }
        Path target = skillDirectory.resolve(relative).normalize();
        if (!target.startsWith(skillDirectory)) {
            return Result.fail("outside_skill_directory");
        }
        String lower = target.getFileName().toString().toLowerCase(Locale.ROOT);
        if (ATTACHMENT_EXTENSIONS.stream().noneMatch(lower::endsWith)) {
            return Result.fail("file_type_denied");
        }
        if (!Files.isRegularFile(target)) {
            return Result.fail("not_found");
        }
        return read(target);
    }

    private Path bodyFile(String name) {
        if (name == null || !name.matches("[a-z0-9_-]{1,64}")) {
            return null;
        }
        for (Path root : new Path[] {user, builtin}) {
            if (root == null) {
                continue;
            }
            Path candidate = root.resolve(name + ".md").normalize();
            if (candidate.startsWith(root) && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Result read(Path path) {
        try {
            if (Files.size(path) > MAX_BYTES) {
                return Result.fail("too_large");
            }
            return Result.ok(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException exception) {
            return Result.fail("unreadable");
        }
    }

    private static Map<String, String> frontmatter(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        String[] lines = text.split("\\R", 20);
        if (lines.length == 0 || !"---".equals(lines[0].strip())) {
            return values;
        }
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index].strip();
            if (line.equals("---")) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                values.put(line.substring(0, colon).strip().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).strip().replaceAll("^\"|\"$", ""));
            }
        }
        return values;
    }

    private static String stem(Path path) {
        String file = path.getFileName().toString();
        return file.substring(0, file.length() - 3).toLowerCase(Locale.ROOT);
    }
}

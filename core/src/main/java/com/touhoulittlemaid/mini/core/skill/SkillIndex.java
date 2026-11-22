package com.touhoulittlemaid.mini.core.skill;

/** The first level of progressive disclosure: one line per skill, no bodies. */
public record SkillIndex(java.util.List<Entry> entries) {

    public record Entry(String name, String description) {
    }

    public SkillIndex {
        entries = entries == null ? java.util.List.of() : java.util.List.copyOf(entries);
    }

    /** Compact text for a model prompt; bounded so the stable prefix stays small. */
    public String promptLines(int limit) {
        StringBuilder out = new StringBuilder();
        int count = Math.min(limit, entries.size());
        for (int index = 0; index < count; index++) {
            Entry entry = entries.get(index);
            out.append("- ").append(entry.name()).append(": ")
                    .append(clip(entry.description(), 120)).append('\n');
        }
        return out.toString().stripTrailing();
    }

    private static String clip(String value, int limit) {
        String text = value == null ? "" : value;
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }
}

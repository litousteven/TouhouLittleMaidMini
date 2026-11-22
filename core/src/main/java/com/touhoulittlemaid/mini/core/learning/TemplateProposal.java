package com.touhoulittlemaid.mini.core.learning;

import java.util.List;
import java.util.Locale;

/**
 * A plan the companion already executed successfully, offered to the player as data.
 *
 * <p>Nothing here can run: a proposal only becomes a {@link PlanTemplate} after
 * {@link TemplateCatalog#adopt(String)}. "Learning" deliberately stops at reviewable data.
 */
public record TemplateProposal(String id, String name, String goal, String description,
        List<TemplateStep> steps, String sourcePlanId, long createdAt, Status status) {

    public enum Status {

        PENDING, ADOPTED, REJECTED;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Status fromId(String value) {
            if (value != null) {
                for (Status status : values()) {
                    if (status.id().equalsIgnoreCase(value.strip())) {
                        return status;
                    }
                }
            }
            return REJECTED;
        }
    }

    public TemplateProposal {
        id = id == null ? "" : id.strip();
        name = name == null ? "" : name.strip();
        goal = goal == null ? "" : goal.strip();
        description = description == null ? "" : description;
        sourcePlanId = sourcePlanId == null ? "" : sourcePlanId;
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (status == null) {
            status = Status.PENDING;
        }
    }
}

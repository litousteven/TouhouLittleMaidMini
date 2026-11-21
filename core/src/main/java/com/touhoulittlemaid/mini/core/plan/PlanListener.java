package com.touhoulittlemaid.mini.core.plan;

/**
 * Observer for plan progress.
 *
 * <p>The loader turns these callbacks into events on the existing bus instead of inventing a
 * second notification path.
 */
public interface PlanListener {

    default void planStarted(Plan plan) {
    }

    default void stepStarted(int index, String module) {
    }

    default void stepFinished(int index, String module, ModuleResult result, boolean skipped) {
    }

    default void planFinished(PlanRunner.Summary summary) {
    }
}

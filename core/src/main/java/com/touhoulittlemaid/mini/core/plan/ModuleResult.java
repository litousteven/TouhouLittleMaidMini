package com.touhoulittlemaid.mini.core.plan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Outcome of one module attempt.
 *
 * <p>{@link #RUNNING} is not a failure: tick-driven modules report it every tick until
 * they finish. Everything else ends the attempt.
 */
public record ModuleResult(Status status, String code, String message, Map<String, Object> data) {

    public enum Status {
        RUNNING,
        OK,
        FAILED,
        TIMED_OUT,
        INTERRUPTED
    }

    public ModuleResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        data = data == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(data));
    }

    public static ModuleResult running() {
        return new ModuleResult(Status.RUNNING, FailureCodes.OK, "", Map.of());
    }

    public static ModuleResult ok(String code, String message) {
        return new ModuleResult(Status.OK, code, message, Map.of());
    }

    public static ModuleResult ok(String code, String message, Map<String, Object> data) {
        return new ModuleResult(Status.OK, code, message, data);
    }

    public static ModuleResult failed(String code, String message) {
        return new ModuleResult(Status.FAILED, code, message, Map.of());
    }

    public static ModuleResult failed(String code, String message, Map<String, Object> data) {
        return new ModuleResult(Status.FAILED, code, message, data);
    }

    public static ModuleResult timedOut(String code, String message) {
        return new ModuleResult(Status.TIMED_OUT, code, message, Map.of());
    }

    public static ModuleResult interrupted(String code, String message) {
        return new ModuleResult(Status.INTERRUPTED, code, message, Map.of());
    }

    public boolean isRunning() {
        return status == Status.RUNNING;
    }

    public boolean isError() {
        return status != Status.OK && status != Status.RUNNING;
    }

    /** True when this result ends the attempt and counts as success. */
    public boolean isSuccess() {
        return status == Status.OK;
    }

    /** Bound output for later {@code $name} references. */
    public Map<String, Object> output() {
        return data;
    }

    /** Convenience for modules that want to report a list under one output key. */
    public static ModuleResult okWithList(String code, String message, String key,
            List<?> values) {
        return ok(code, message, Map.of(key, List.copyOf(values)));
    }
}

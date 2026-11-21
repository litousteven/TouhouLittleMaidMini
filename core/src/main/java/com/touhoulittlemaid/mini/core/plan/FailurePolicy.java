package com.touhoulittlemaid.mini.core.plan;

import java.util.Locale;

/**
 * What the runner does when a step fails.
 *
 * <p>Parsed from {@code on_failure}: {@code abort} (default), {@code continue} or
 * {@code retry:N} with N at most {@link PlanLimits#MAX_RETRIES}.
 */
public record FailurePolicy(Kind kind, int retries) {

    public enum Kind {
        ABORT,
        CONTINUE,
        RETRY
    }

    public FailurePolicy {
        if (kind == null) {
            throw new IllegalArgumentException("failure policy kind is required");
        }
        if (retries < 0 || retries > PlanLimits.MAX_RETRIES) {
            throw new IllegalArgumentException("retries must be within 0.." + PlanLimits.MAX_RETRIES);
        }
    }

    public static FailurePolicy abort() {
        return new FailurePolicy(Kind.ABORT, 0);
    }

    public static FailurePolicy continueStep() {
        return new FailurePolicy(Kind.CONTINUE, 0);
    }

    public static FailurePolicy retry(int retries) {
        return new FailurePolicy(Kind.RETRY, retries);
    }

    public static FailurePolicy parse(String text) throws PlanFormatException {
        if (text == null || text.isBlank()) {
            return abort();
        }
        String value = text.strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "abort" -> abort();
            case "continue" -> continueStep();
            default -> {
                if (!value.startsWith("retry:")) {
                    throw new PlanFormatException(FailureCodes.INVALID_POLICY,
                            "on_failure must be abort, continue or retry:N, got " + text);
                }
                String amount = value.substring("retry:".length());
                int retries;
                try {
                    retries = Integer.parseInt(amount);
                } catch (NumberFormatException exception) {
                    throw new PlanFormatException(FailureCodes.INVALID_POLICY,
                            "retry count is not a number: " + text);
                }
                if (retries < 0 || retries > PlanLimits.MAX_RETRIES) {
                    throw new PlanFormatException(FailureCodes.INVALID_POLICY,
                            "retry count must be 0.." + PlanLimits.MAX_RETRIES + ", got "
                                    + retries);
                }
                yield retry(retries);
            }
        };
    }
}

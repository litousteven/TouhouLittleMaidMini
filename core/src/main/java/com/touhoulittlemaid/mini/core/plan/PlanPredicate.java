package com.touhoulittlemaid.mini.core.plan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The closed predicate grammar allowed in {@code if} and {@code repeat.while}.
 *
 * <p>There is no general expression evaluator here, on purpose. Each form is a fixed
 * pattern over a fixed {@link PredicateContext} accessor, so a plan cannot reach anything
 * the body does not already expose.
 */
public interface PlanPredicate {

    boolean test(PredicateContext context);

    String source();

    /** Parses one whitelisted predicate or throws with the offending text. */
    static PlanPredicate parse(String text) throws PlanFormatException {
        if (text == null || text.isBlank()) {
            throw new PlanFormatException(FailureCodes.UNKNOWN_PREDICATE, "predicate is empty");
        }
        String value = text.strip();
        if (value.length() > PlanLimits.MAX_PREDICATE_LENGTH) {
            throw new PlanFormatException(FailureCodes.UNKNOWN_PREDICATE,
                    "predicate is longer than " + PlanLimits.MAX_PREDICATE_LENGTH
                            + " characters");
        }
        PlanPredicate parsed = StepPredicate.parse(value);
        if (parsed == null) {
            parsed = BoundPredicate.parse(value);
        }
        if (parsed == null) {
            parsed = InventoryHasPredicate.parse(value);
        }
        if (parsed == null) {
            parsed = ComparePredicate.parse(value);
        }
        if (parsed == null) {
            parsed = ConstantPredicate.parse(value);
        }
        if (parsed == null) {
            throw new PlanFormatException(FailureCodes.UNKNOWN_PREDICATE,
                    "unsupported predicate: " + value);
        }
        return parsed;
    }

    /** Shared comparison used by every numeric form. */
    record Relation(String operator, int value) {

        static final Pattern PATTERN = Pattern.compile("(==|!=|>=|<=|>|<)\\s*(\\d{1,6})");

        static Relation parse(String text) {
            Matcher matcher = PATTERN.matcher(text.strip());
            if (!matcher.matches()) {
                return null;
            }
            return new Relation(matcher.group(1), Integer.parseInt(matcher.group(2)));
        }

        boolean test(int actual) {
            return switch (operator) {
                case "==" -> actual == value;
                case "!=" -> actual != value;
                case ">=" -> actual >= value;
                case "<=" -> actual <= value;
                case ">" -> actual > value;
                case "<" -> actual < value;
                default -> false;
            };
        }

        boolean test(double actual) {
            return switch (operator) {
                case "==" -> actual == value;
                case "!=" -> actual != value;
                case ">=" -> actual >= value;
                case "<=" -> actual <= value;
                case ">" -> actual > value;
                case "<" -> actual < value;
                default -> false;
            };
        }
    }

    record StepPredicate(String stepId, boolean expectOk, String source)
            implements PlanPredicate {

        private static final Pattern PATTERN =
                Pattern.compile("step\\.(ok|failed)\\(\"([A-Za-z0-9_]{1,32})\"\\)");

        static PlanPredicate parse(String text) {
            Matcher matcher = PATTERN.matcher(text);
            if (!matcher.matches()) {
                return null;
            }
            return new StepPredicate(matcher.group(2), "ok".equals(matcher.group(1)), text);
        }

        @Override
        public boolean test(PredicateContext context) {
            return expectOk ? context.stepOk(stepId) : context.stepFailed(stepId);
        }
    }

    record BoundPredicate(String name, boolean empty, Relation relation, String source)
            implements PlanPredicate {

        private static final Pattern PATTERN = Pattern.compile(
                "bound\\.(empty|count)\\(\"\\$([A-Za-z_][A-Za-z0-9_]*)\"\\)(?:\\s*(.*))?");

        static PlanPredicate parse(String text) {
            Matcher matcher = PATTERN.matcher(text);
            if (!matcher.matches()) {
                return null;
            }
            String tail = matcher.group(3) == null ? "" : matcher.group(3).strip();
            Relation relation = tail.isEmpty() ? null : Relation.parse(tail);
            if (!tail.isEmpty() && relation == null) {
                return null;
            }
            return new BoundPredicate(matcher.group(2), "empty".equals(matcher.group(1)),
                    relation, text);
        }

        @Override
        public boolean test(PredicateContext context) {
            if (empty) {
                return context.boundEmpty(name);
            }
            int count = context.boundCount(name);
            return relation == null ? count > 0 : relation.test(count);
        }
    }

    record InventoryHasPredicate(String item, int amount, String source)
            implements PlanPredicate {

        private static final Pattern PATTERN = Pattern.compile(
                "inventory\\.has\\(\"([#a-z0-9_.:/-]{1,120})\",\\s*(\\d{1,4})\\)");

        static PlanPredicate parse(String text) {
            Matcher matcher = PATTERN.matcher(text);
            if (!matcher.matches()) {
                return null;
            }
            return new InventoryHasPredicate(matcher.group(1),
                    Integer.parseInt(matcher.group(2)), text);
        }

        @Override
        public boolean test(PredicateContext context) {
            return context.inventoryCount(item) >= amount;
        }
    }

    record ComparePredicate(String accessor, String operand, Relation relation, String source)
            implements PlanPredicate {

        private static final Pattern PATTERN = Pattern.compile(
                "(inventory\\.space\\(\\)|entities\\.hostiles\\((\\d{1,3})\\)|owner\\.distance\\(\\))"
                        + "\\s*(==|!=|>=|<=|>|<)\\s*(\\d{1,6})");

        static PlanPredicate parse(String text) {
            Matcher matcher = PATTERN.matcher(text.strip());
            if (!matcher.matches()) {
                return null;
            }
            return new ComparePredicate(matcher.group(1), matcher.group(2),
                    new Relation(matcher.group(3), Integer.parseInt(matcher.group(4))), text);
        }

        @Override
        public boolean test(PredicateContext context) {
            if (accessor.startsWith("inventory.space")) {
                return relation.test(context.inventorySpace());
            }
            if (accessor.startsWith("entities.hostiles")) {
                return relation.test(context.hostileCount(Integer.parseInt(operand)));
            }
            return relation.test(context.ownerDistance());
        }
    }

    record ConstantPredicate(Kind kind, String source) implements PlanPredicate {

        enum Kind {
            DAY,
            RAIN
        }

        static PlanPredicate parse(String text) {
            return switch (text) {
                case "time.is_day" -> new ConstantPredicate(Kind.DAY, text);
                case "weather.is_rain" -> new ConstantPredicate(Kind.RAIN, text);
                default -> null;
            };
        }

        @Override
        public boolean test(PredicateContext context) {
            return kind == Kind.DAY ? context.isDay() : context.isRain();
        }
    }
}

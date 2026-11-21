package com.touhoulittlemaid.mini.core.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Only the declared predicate forms exist; anything else is a hard parse error. */
class PlanPredicateTest {

    private final TestModules.FakePredicates context = new TestModules.FakePredicates();

    @Test
    void evaluatesEveryWhitelistedForm() throws Exception {
        context.answers.put("ok:crops", true);
        context.answers.put("failed:dig", true);
        context.answers.put("empty:bag", true);
        context.answers.put("day", true);
        context.answers.put("rain", true);
        context.counts.put("bound:items", 5);
        context.counts.put("inv:#c:ores", 3);
        context.counts.put("space", 4);
        context.counts.put("hostiles:16", 0);
        context.counts.put("owner", 3);

        assertTrue(check("step.ok(\"crops\")"));
        assertTrue(check("step.failed(\"dig\")"));
        assertTrue(check("bound.empty(\"$bag\")"));
        assertTrue(check("bound.count(\"$items\") > 0"));
        assertTrue(check("bound.count(\"$items\") == 5"));
        assertTrue(check("inventory.has(\"#c:ores\", 3)"));
        assertTrue(check("inventory.space() > 2"));
        assertTrue(check("entities.hostiles(16) == 0"));
        assertTrue(check("owner.distance() < 8"));
        assertTrue(check("time.is_day"));
        assertTrue(check("weather.is_rain"));

        assertFalse(check("step.ok(\"wheat\")"));
        assertFalse(check("inventory.has(\"#c:ores\", 9)"));
        assertFalse(check("owner.distance() > 8"));
    }

    @Test
    void rejectsAnythingOutsideTheWhitelist() {
        String[] rejected = {
                "java.lang.Runtime.getRuntime().exec(\"ls\")",
                "time.is_night",
                "owner.distance() < $x",
                "bound.count(\"$items\") = 3",
                "inventory.has(\"\", 1)",
                "step.ok(\"a b\")"
        };
        for (String text : rejected) {
            PlanFormatException exception = assertThrows(PlanFormatException.class,
                    () -> PlanPredicate.parse(text), text);
            assertEquals(FailureCodes.UNKNOWN_PREDICATE, exception.code(), text);
        }
    }

    @Test
    void rejectsOverlongPredicate() {
        String text = "bound.count(\"$" + "x".repeat(PlanLimits.MAX_PREDICATE_LENGTH) + "\") > 0";
        assertThrows(PlanFormatException.class, () -> PlanPredicate.parse(text));
    }

    @Test
    void rejectsEmptyPredicate() {
        assertThrows(PlanFormatException.class, () -> PlanPredicate.parse("   "));
    }

    private boolean check(String text) throws Exception {
        return PlanPredicate.parse(text).test(context);
    }
}

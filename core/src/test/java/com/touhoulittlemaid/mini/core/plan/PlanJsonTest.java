package com.touhoulittlemaid.mini.core.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Strict decoding of plan JSON. Unknown shapes must fail instead of being guessed. */
class PlanJsonTest {

    private static String plan(String steps) {
        return "{\"version\":1,\"goal\":\"test\",\"steps\":[" + steps + "]}";
    }

    @Test
    void parsesFullStepSurface() throws Exception {
        String json = plan("{\"module\":\"probe\",\"params\":{\"tier\":2,\"label\":\"hi\"},"
                + "\"bind\":\"first\",\"if\":\"time.is_day\",\"on_failure\":\"retry:1\","
                + "\"repeat\":{\"max\":3,\"while\":\"bound.count(\\\"$first\\\") > 0\"}}");
        ParsedPlan parsed = PlanJson.parse(json);
        PlanStep step = parsed.plan().steps().get(0);
        assertEquals("probe", step.module());
        assertEquals("first", step.bind());
        assertEquals("time.is_day", step.condition());
        assertEquals(FailurePolicy.Kind.RETRY, step.onFailure().kind());
        assertEquals(1, step.onFailure().retries());
        assertEquals(3, step.repeat().max());
        assertTrue(step.params().integer("tier", -1) == 2);
        assertEquals("hi", step.params().string("label", ""));
        assertEquals(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                parsed.bytes());
    }

    @Test
    void rejectsUnknownTopLevelField() {
        PlanFormatException exception = assertThrows(PlanFormatException.class,
                () -> PlanJson.parse("{\"version\":1,\"steps\":[],\"command\":\"rm -rf /\"}"));
        assertEquals(FailureCodes.INVALID_PLAN, exception.code());
        assertTrue(exception.getMessage().contains("command"));
    }

    @Test
    void rejectsUnknownStepField() {
        PlanFormatException exception = assertThrows(PlanFormatException.class,
                () -> PlanJson.parse(plan(
                        "{\"module\":\"probe\",\"script\":\"run()\"}")));
        assertEquals(FailureCodes.INVALID_PLAN, exception.code());
        assertTrue(exception.getMessage().contains("script"));
    }

    @Test
    void rejectsBadModuleId() {
        assertThrows(PlanFormatException.class, () -> PlanJson.parse(plan(
                "{\"module\":\"Shell Exec\"}")));
    }

    @Test
    void rejectsNonObjectParams() {
        PlanFormatException exception = assertThrows(PlanFormatException.class,
                () -> PlanJson.parse(plan("{\"module\":\"probe\",\"params\":[1,2]}")));
        assertEquals(FailureCodes.INVALID_PARAMS, exception.code());
    }

    @Test
    void rejectsTooManyRetries() {
        PlanFormatException exception = assertThrows(PlanFormatException.class,
                () -> PlanJson.parse(plan(
                        "{\"module\":\"probe\",\"on_failure\":\"retry:3\"}")));
        assertEquals(FailureCodes.INVALID_POLICY, exception.code());
    }

    @Test
    void rejectsUnknownVersion() {
        PlanFormatException exception = assertThrows(PlanFormatException.class,
                () -> PlanJson.parse("{\"version\":2,\"steps\":[]}"));
        assertEquals(FailureCodes.UNKNOWN_VERSION, exception.code());
    }

    @Test
    void rejectsTooManyStepsAndOversizedPayloadsEarly() {
        StringBuilder steps = new StringBuilder();
        for (int index = 0; index < PlanLimits.MAX_STEPS + 1; index++) {
            if (index > 0) {
                steps.append(',');
            }
            steps.append("{\"module\":\"probe\"}");
        }
        PlanFormatException tooMany = assertThrows(PlanFormatException.class,
                () -> PlanJson.parse(plan(steps.toString())));
        assertEquals(FailureCodes.TOO_MANY_STEPS, tooMany.code());

        String huge = "{\"version\":1,\"goal\":\"" + "x".repeat(PlanLimits.MAX_PLAN_BYTES)
                + "\",\"steps\":[]}";
        PlanFormatException tooBig = assertThrows(PlanFormatException.class,
                () -> PlanJson.parse(huge));
        assertEquals(FailureCodes.PLAN_TOO_LARGE, tooBig.code());
    }

    @Test
    void malformedJsonIsRejectedWithoutStackTraceLeaking() {
        PlanFormatException exception = assertThrows(PlanFormatException.class,
                () -> PlanJson.parse("{not json"));
        assertEquals(FailureCodes.INVALID_PLAN, exception.code());
        assertFalse(exception.getMessage().contains("\tat "));
    }

    @Test
    void writeRoundTrips() throws Exception {
        Plan original = PlanJson.parse(plan("""
                {"module":"probe","params":{"tier":1},"bind":"a","on_failure":"continue",
                 "repeat":{"max":2}}
                """)).plan();
        ParsedPlan again = PlanJson.parse(PlanJson.write(original));
        PlanStep step = again.plan().steps().get(0);
        assertEquals("a", step.bind());
        assertEquals(FailurePolicy.Kind.CONTINUE, step.onFailure().kind());
        assertEquals(2, step.repeat().max());
        assertEquals(1, step.params().integer("tier", -1));
    }
}

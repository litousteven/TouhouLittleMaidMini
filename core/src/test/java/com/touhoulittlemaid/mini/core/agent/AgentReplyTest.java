package com.touhoulittlemaid.mini.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentReplyTest {

    private static final String PLAN = """
            {"version":1,"goal":"harvest","steps":[{"module":"harvest_area","params":{"radius":4}}]}
            """;

    @Test
    void acceptsaSayAndAPlan() {
        AgentReply reply = AgentReply.parse("""
                {"say":"我去收菜。","plan":%s}
                """.formatted(PLAN));
        assertTrue(reply.ok());
        assertEquals("我去收菜。", reply.say());
        assertTrue(reply.hasPlan());
        assertTrue(reply.planJson().contains("harvest_area"));
    }

    @Test
    void acceptsChatOnly() {
        AgentReply reply = AgentReply.parse("{\"say\":\"好的，我在这儿等你。\",\"plan\":null}");
        assertTrue(reply.ok());
        assertFalse(reply.hasPlan());
        assertEquals("好的，我在这儿等你。", reply.say());
    }

    @Test
    void survivesFencesAndProse() {
        AgentReply reply = AgentReply.parse("""
                Sure, here you go:
                ```json
                {"say":"收到","plan":%s}
                ```
                Tell me if that is wrong.
                """.formatted(PLAN));
        assertTrue(reply.ok(), reply.message());
        assertTrue(reply.hasPlan());
    }

    @Test
    void acceptsABarePlan() {
        AgentReply reply = AgentReply.parse(PLAN);
        assertTrue(reply.ok(), reply.message());
        assertTrue(reply.hasPlan());
        assertEquals("", reply.say());
    }

    @Test
    void acceptsAPlanWrappedInAString() {
        AgentReply reply = AgentReply.parse("""
                {"say":"好","plan":"{\\"version\\":1,\\"steps\\":[{\\"module\\":\\"say\\",\\"params\\":{\\"text\\":\\"hi\\"}}]}"}
                """);
        assertTrue(reply.ok(), reply.message());
        assertTrue(reply.hasPlan());
    }

    @Test
    void rejectsProseWithoutJson() {
        AgentReply reply = AgentReply.parse("I will go and harvest the wheat now.");
        assertFalse(reply.ok());
        assertEquals(AgentReply.INVALID_REPLY, reply.code());
        assertFalse(reply.hasPlan());
    }

    @Test
    void rejectsAnEmptyAnswer() {
        AgentReply reply = AgentReply.parse("   ");
        assertFalse(reply.ok());
        assertEquals(AgentReply.EMPTY_REPLY, reply.code());
    }

    @Test
    void rejectsAPlanOfTheWrongType() {
        AgentReply reply = AgentReply.parse("{\"say\":\"x\",\"plan\":42}");
        assertFalse(reply.ok());
        assertEquals(AgentReply.INVALID_PLAN, reply.code());
    }

    @Test
    void treatsAnEmptyStepListAsNoPlan() {
        AgentReply reply = AgentReply.parse("{\"say\":\"不用做\",\"plan\":{\"version\":1,\"steps\":[]}}");
        assertTrue(reply.ok());
        assertFalse(reply.hasPlan());
    }

    @Test
    void rejectsAnAnswerWithNeitherTextNorPlan() {
        AgentReply reply = AgentReply.parse("{\"plan\":null}");
        assertFalse(reply.ok());
        assertEquals(AgentReply.INVALID_REPLY, reply.code());
    }

    @Test
    void truncatesALongSay() {
        AgentReply reply = AgentReply.parse("{\"say\":\"" + "啊".repeat(600) + "\",\"plan\":null}");
        assertTrue(reply.ok());
        assertEquals(400, reply.say().length());
    }

    @Test
    void findsDescribeRequestsSeparately() {
        assertEquals("craft_item", AgentReply.describeRequest("{\"describe\":\"craft_item\"}"));
        assertNull(AgentReply.describeRequest("{\"say\":\"hi\",\"plan\":null}"));
        assertNull(AgentReply.describeRequest("not json"));
    }

    @Test
    void bracesInsideStringsDoNotEndTheObject() {
        AgentReply reply = AgentReply.parse("{\"say\":\"use {this} carefully\",\"plan\":null}");
        assertTrue(reply.ok(), reply.message());
        assertEquals("use {this} carefully", reply.say());
    }
}

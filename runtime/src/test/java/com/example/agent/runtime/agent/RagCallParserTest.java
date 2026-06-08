package com.example.agent.runtime.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RagCallParserTest {
    @Test
    void parsesMultiLineRagCall() {
        var parsed = RagCallParser.parse("""
                RAG:
                query: Apache Pekko supervision
                topK: 2
                """);

        assertTrue(parsed.isPresent());
        assertEquals("Apache Pekko supervision", parsed.get().query());
        assertEquals(2, parsed.get().topK());
    }

    @Test
    void parsesInlineQueryWithDefaultTopK() {
        var parsed = RagCallParser.parse("RAG: typed actor testing");

        assertTrue(parsed.isPresent());
        assertEquals("typed actor testing", parsed.get().query());
        assertEquals(3, parsed.get().topK());
    }
}

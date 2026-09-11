package com.codexrefresh.app

import org.junit.Assert.*
import org.junit.Test

class SseParserTest {
    @Test fun dataOnlyEventIsFramed() {
        val p = SseParser()
        assertNull(p.accept("data: {\"type\":\"response.completed\"}"))
        assertEquals("{\"type\":\"response.completed\"}", p.accept(""))
    }
    @Test fun crlfAndMultilineDataAreHandled() {
        val p = SseParser()
        assertNull(p.accept("data: {"))
        assertEquals("{\n\"type\": \"x\"}", p.accept("data: \"type\": \"x\"}\r" ).let { p.accept("") })
    }
    @Test fun doneIsIgnored() { val p = SseParser(); assertNull(p.accept("data: [DONE]")); assertNull(p.accept("")) }
    @Test fun oversizedPayloadIsDropped() { val p = SseParser(8); assertNull(p.accept("data: 123456789")); assertNull(p.finish()) }
    @Test fun malformedPayloadStillFramesForClientValidation() { val p = SseParser(); p.accept("data: not-json"); assertEquals("not-json", p.accept("")) }
    @Test fun failedEventIsFramed() { val p = SseParser(); p.accept("data: {\"type\":\"response.failed\"}"); assertEquals("{\"type\":\"response.failed\"}", p.accept("")) }
}

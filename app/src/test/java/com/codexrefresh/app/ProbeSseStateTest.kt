package com.codexrefresh.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeSseStateTest {
    @Test fun completedEventCollectsDeltaAndUsage() {
        val state = ProbeSseState()
        assertEquals(false, state.consume("""{"type":"response.output_text.delta","delta":"PI_ANDROID_KICK_test"}"""))
        assertEquals(
            true,
            state.consume(
                """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":12,"output_tokens":3,"input_tokens_details":{"cached_tokens":4}}}}""",
            ),
        )
        assertEquals(ProbeStreamResult("PI_ANDROID_KICK_test", 12, 3, 4), state.result())
    }

    @Test fun doneEventCanReadNestedFinalOutput() {
        val state = ProbeSseState()
        assertTrue(
            state.consume(
                """{"type":"response.done","response":{"status":"completed","output":[{"content":[{"type":"output_text","text":"ok"}]}],"usage":{}}}""",
            ),
        )
        assertEquals("ok", state.result().text)
    }

    @Test fun incompleteTerminalStatusIsRejected() {
        val state = ProbeSseState()
        val error = assertThrows(IllegalStateException::class.java) {
            state.consume("""{"type":"response.completed","response":{"status":"incomplete"}}""")
        }
        assertTrue(error.message.orEmpty().contains("终态异常"))
    }

    @Test fun explicitFailureIsRejectedAndTokenTextIsRedacted() {
        val state = ProbeSseState()
        val error = assertThrows(IllegalStateException::class.java) {
            state.consume("""{"type":"response.failed","response":{"error":{"message":"access_token=secret"}}}""")
        }
        assertTrue(error.message.orEmpty().contains("token=redacted"))
        assertEquals(false, error.message.orEmpty().contains("secret"))
    }

    @Test fun malformedJsonIsRejected() {
        assertThrows(IllegalStateException::class.java) { ProbeSseState().consume("not-json") }
    }

    @Test fun outputIsBounded() {
        val state = ProbeSseState(maxOutputChars = 4)
        state.consume("""{"type":"response.output_text.delta","delta":"123456"}""")
        state.consume("""{"type":"response.completed","response":{"status":"completed","usage":{}}}""")
        assertEquals("1234", state.result().text)
    }
}

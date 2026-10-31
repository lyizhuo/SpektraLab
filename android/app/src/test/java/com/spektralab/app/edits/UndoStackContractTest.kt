package com.spektralab.app.edits

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `record` promises its caller.
 *
 * `EditorViewModel.setParams` used to do:
 *
 *     val merged = undoStack.record(before, fields, now)
 *     if (!merged) undoStack.record(before, fields, now)
 *
 * which reads as "record it if the first call did not merge it". But `record`
 * *pushes the step itself* when it cannot merge, and returns false to say so —
 * so the second call pushed the same snapshot again. These cases state the
 * contract the caller can rely on, which makes the double call visibly wrong
 * rather than merely suspicious.
 */
class UndoStackContractTest {

    private fun snapshot(exposure: Double) = JSONObject().put("print_exposure", exposure)

    @Test
    fun oneRecordIsOneStep() {
        val stack = UndoStack()
        assertFalse(stack.record(snapshot(1.0), setOf("print_exposure"), 1_000))
        assertEquals("a single record must produce a single step", 1, stack.depth)
    }

    @Test
    fun recordingTheSameStepTwicePushesItTwice() {
        val stack = UndoStack()
        stack.record(snapshot(1.0), setOf("print_exposure"), 1_000)
        stack.record(snapshot(1.0), setOf("print_exposure"), 5_000)
        assertEquals(
            "outside the coalescing window the same snapshot is a new step; two calls are two steps",
            2,
            stack.depth,
        )
    }

    @Test
    fun aChangeInsideTheWindowMergesInsteadOfGrowingTheStack() {
        val stack = UndoStack()
        stack.record(snapshot(1.0), setOf("print_exposure"), 1_000)
        assertTrue(
            "the same field 100 ms later is the same drag",
            stack.record(snapshot(1.0), setOf("print_exposure"), 1_100),
        )
        assertEquals(1, stack.depth)
    }

    @Test
    fun aDifferentFieldSetIsANewStepEvenWhenItIsFast() {
        val stack = UndoStack()
        stack.record(snapshot(1.0), setOf("print_exposure"), 1_000)
        assertFalse(stack.record(snapshot(1.0), setOf("m_filter_shift"), 1_100))
        assertEquals("a different control is a different step", 2, stack.depth)
    }
}

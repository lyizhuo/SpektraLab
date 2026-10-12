package com.spektralab.app.edits

import org.json.JSONObject

/**
 * Undo, at the granularity a hand produces (plan S6): one slider drag is one
 * step, decided by a silence window rather than by the slider's own events.
 *
 * The stack holds wire-param snapshots, which is what `spk_set_params` takes,
 * so undoing is `setParams(previous)` and nothing else has to know what a
 * field means. A step is *the parameters before the change*, plus the fields
 * it changed, which is what makes the next change inside
 * [COALESCE_WINDOW_MS] merge into the same step instead of pushing a new one.
 */
class UndoStack(private val limit: Int = DEFAULT_LIMIT) {

    data class Step(val params: JSONObject, val fields: Set<String>, val atMillis: Long)

    private val past = ArrayDeque<Step>()
    private val future = ArrayDeque<Step>()

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()
    val depth: Int get() = past.size

    /**
     * Records the state *before* a change. Returns whether the change was
     * merged into the previous step (the caller does not care, but the UI's
     * "undo" label does).
     */
    fun record(before: JSONObject, changedFields: Set<String>, nowMillis: Long): Boolean {
        val last = past.lastOrNull()
        val merged = last != null &&
            changedFields.isNotEmpty() &&
            changedFields == last.fields &&
            nowMillis - last.atMillis <= COALESCE_WINDOW_MS
        if (merged) {
            // Same slider, still moving: keep the older snapshot (the state
            // before the drag started), only the clock moves.
            past.removeLast()
            past.addLast(last.copy(atMillis = nowMillis))
        } else {
            past.addLast(Step(before, changedFields, nowMillis))
            while (past.size > limit) past.removeFirst()
        }
        future.clear()
        return merged
    }

    /** The state to restore, or null when there is nothing to undo. */
    fun undo(): JSONObject? {
        val step = past.removeLastOrNull() ?: return null
        future.addLast(step)
        return step.params
    }

    fun redo(): JSONObject? {
        val step = future.removeLastOrNull() ?: return null
        past.addLast(step)
        return step.params
    }

    fun clear() {
        past.clear()
        future.clear()
    }

    companion object {
        const val DEFAULT_LIMIT = 64
        const val COALESCE_WINDOW_MS = 400L
    }
}

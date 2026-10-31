package com.spektralab.app.rolls

/**
 * A film roll: the prototype's organising idea, and the one model the desktop
 * app does not have.
 *
 * C1 shows "我的胶卷 · Portra 160 · 17 张 · 今天" with a progress bar, and C3
 * prints `135 · 36 EXP` above the viewfinder with a `17 / 36` counter at the
 * bottom. That is not a folder or a filter over the library — it is a **roll**
 * with a capacity, a film stock and a paper stock fixed at the moment it was
 * loaded, and photographs filled into it in order.
 *
 * Keeping capacity on the roll rather than on the film profile is deliberate:
 * the same Portra 400 comes in 135 (36 exp), 120 (12 exp) and sheet formats, so
 * "how many frames fit" is a property of *this* roll, not of the emulsion.
 *
 * Pure data — no `org.json`, no `Context` — so the arithmetic (how many are
 * left, is it full, what the editor's badge says) is testable on the JVM. The
 * persistence lives in [RollStore].
 */
data class Roll(
    val id: String,
    val filmStockId: String,
    val paperStockId: String,
    /** Frames this roll holds. 36 for 135, 12 for 120 — from the frame format. */
    val capacity: Int,
    val createdAtMillis: Long,
    /** Frame identities in the order they were shot or imported. */
    val frameIds: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = frameIds.isEmpty()

    val count: Int get() = frameIds.size

    val remaining: Int get() = (capacity - frameIds.size).coerceAtLeast(0)

    val isFull: Boolean get() = frameIds.size >= capacity

    /** `17 / 36` — the C1 card and the C3 counter. */
    val counterLabel: String get() = "$count / $capacity"

    /**
     * The next frame's number, 1-based, or null when the roll is full.
     *
     * The frame number is what the film-edge and date-back features print, which
     * is why it is derived here rather than at the point of drawing: a number
     * that disagrees with the roll's count is a wrong photograph, not a wrong
     * label.
     */
    val nextFrameNumber: Int? get() = if (isFull) null else count + 1

    /**
     * Append a frame. Returns null — not a shortened roll — when the roll is
     * full, because silently dropping the 37th photograph is the failure this
     * model exists to prevent.
     */
    fun append(frameId: String): Roll? {
        if (isFull) return null
        if (frameId in frameIds) return this
        return copy(frameIds = frameIds + frameId)
    }

    /** Remove a frame; unknown ids are a no-op rather than an error. */
    fun remove(frameId: String): Roll = copy(frameIds = frameIds - frameId)

    /** The fraction filled, for the C1 progress bar. */
    fun progress(): Float =
        if (capacity <= 0) 0f else (count.toFloat() / capacity).coerceIn(0f, 1f)

    companion object {
        /** 135 · 36 EXP, the default the prototype prints. */
        const val CAPACITY_135: Int = 36

        /** 120 · 12 EXP. */
        const val CAPACITY_120: Int = 12

        /**
         * A new roll. Ids are time-ordered so the directory listing sorts by
         * age without reading every file.
         */
        fun newRoll(
            id: String,
            filmStockId: String,
            paperStockId: String,
            capacity: Int = CAPACITY_135,
            nowMillis: Long = System.currentTimeMillis(),
        ): Roll = Roll(
            id = id,
            filmStockId = filmStockId,
            paperStockId = paperStockId,
            capacity = capacity.coerceAtLeast(1),
            createdAtMillis = nowMillis,
        )
    }
}

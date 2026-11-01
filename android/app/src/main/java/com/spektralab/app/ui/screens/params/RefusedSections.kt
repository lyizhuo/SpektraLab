package com.spektralab.app.ui.screens.params

/**
 * The five features this backend refuses, and the sentence that says so.
 *
 * The engine names them in `backend.unsupported_features` (`engine.cpp:320-325`,
 * plan D6). The prototype draws 片边, 日期 and 场景置位 as if they worked; this
 * build cannot, so each one is shown **in place and disabled, with the reason on
 * it** rather than hidden. A hole where a control used to be is worse than a
 * control that says what it cannot do.
 *
 * The map is family → (label, why). [of] takes a family name and returns the
 * sentence, so the screens never invent their own wording and the test can hold
 * all five to the same bar: a reason is a sentence about the picture, not a
 * status code.
 */
object RefusedSections {

    /** One refused family: what the prototype calls it, and why it is off. */
    data class Section(val family: String, val label: String, val detail: String)

    private val sections: List<Section> = listOf(
        Section(
            family = "overscan",
            label = "片边",
            detail = "引擎的非 Apple 后端没有实现片边：边缘的孔、齿孔与漏光要由引擎按画幅生成，这一版产不出来。",
        ),
        Section(
            family = "date_imprint",
            label = "日期打印",
            detail = "日期后背的成像需要引擎在片边层写字符，这一版没有实现，打开它也不会出现在照片上。",
        ),
        Section(
            family = "scene_latitude_mapping",
            label = "场景置位",
            detail = "场景宽容度可以测量，但把测量结果施加到画面上需要引擎的场景置位阶段，这一版没有实现。",
        ),
        Section(
            family = "contrast_mask",
            label = "对比遮罩",
            detail = "虚拟对比遮罩是 Apple 后端的过程，这一版没有实现；高光与阴影拉回暂时不可用。",
        ),
        Section(
            family = "digital_intermediate",
            label = "数字中间片",
            detail = "DI 中间片的写入需要引擎的 DI 导出路径，这一版没有实现；导出仍是照片级 TIFF／PNG。",
        ),
    )

    /** The families in the design's order, not a set's. */
    val families: List<String> = sections.map { it.family }

    fun of(family: String): Section? = sections.firstOrNull { it.family == family }

    /** The sentence to put under a disabled control, or null if it is not refused. */
    fun explain(family: String): String? = of(family)?.detail

    /** The label the prototype uses for a family. */
    fun label(family: String): String = of(family)?.label ?: family
}

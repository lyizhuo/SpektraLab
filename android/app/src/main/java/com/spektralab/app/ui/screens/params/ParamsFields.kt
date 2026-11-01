package com.spektralab.app.ui.screens.params

/**
 * The wire names the parameter page writes, in one place.
 *
 * The page itself reads and writes these keys inline — a Compose screen cannot
 * carry a catalogue the way the old sheet did without turning every row into
 * data — so this object is the catalogue *for the test*: it is the list
 * `ParamFieldParityTest` checks against the engine's captured `params_schema`
 * and `capabilities`, which is the rule that matters (the UI may not name a
 * field the engine does not have, nor offer a control for a feature this
 * backend refuses).
 *
 * Keep it in step with [ParamsScreen]. A name added to the screen and not to
 * this list is a name nobody checks; a name here that the screen does not use
 * is harmless but pointless.
 */
object ParamsFields {

    /** Every field 显影前 writes. */
    val before: List<String> = listOf(
        "camera.exposure_compensation_ev",
        "camera.auto_exposure_method",
        "camera.film_format_mm",
        "film.grain_active",
        "film.halation_active",
    )

    /** Every field 显影后 writes. */
    val after: List<String> = listOf(
        "print.y_filter_neutral",
        "print.m_filter_neutral",
        "print.c_filter_neutral",
        "print.print_exposure",
        "print.contrast_mask_highlights",
        "film.density_curve_gamma",
    )

    /** The leaf name of a dotted wire path, which is what the schema declares. */
    fun leaf(wire: String): String = wire.substringAfterLast('.')

    val wireNames: List<String> = (before + after).distinct()

    /** The leaf names, which is the form `params_schema` uses. */
    val leaves: List<String> = wireNames.map(::leaf).distinct()

    /**
     * The families the engine refuses on this backend, and the fields that
     * belong to them.
     *
     * The page shows 场景置位 and A2 shows 片边 / 日期, but neither writes: the
     * rows are disabled with the reason on them, which is why no field from
     * these families appears above.
     */
    val refusedFamilies: Map<String, List<String>> = mapOf(
        "overscan" to listOf("overscan_active", "overscan_holes", "overscan_edge_text"),
        "date_imprint" to listOf("date_imprint_active", "date_imprint_text"),
        "scene_latitude_mapping" to listOf("scene_latitude_active", "scene_latitude_max_lift"),
        "contrast_mask" to listOf("contrast_mask_active", "contrast_mask_scale"),
        "digital_intermediate" to listOf("digital_intermediate", "digital_intermediate_blue_compensation"),
    )
}

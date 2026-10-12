package com.spektralab.app.ui

import org.json.JSONObject

/**
 * The one place the UI spells engine wire names.
 *
 * Plan S5 makes two rules hard, and this file is how both of them are kept:
 *
 *  * **Every control is generated from `spk_params_schema`.** A panel knows a
 *    [ParamField] and nothing else: the range comes from the schema's own
 *    `range`, the current value from the session's `params`, the type from
 *    `type`. Nothing below the catalogue names a wire field, so
 *    `ParamFieldParityTest` can prove in a unit test that every name the UI
 *    can send exists in the captured schema — and that none of them is one of
 *    the five features this backend refuses by name
 *    (`engine/src/pipeline/engine.cpp:320-325`).
 *  * **`unsupported_features` hides controls, it does not disable them**
 *    (plan D6). [familyOf] maps a wire name to the feature it belongs to, and
 *    [visibleFields] drops a field whose family this device does not have.
 *
 * The catalogue is also where a field's *group* lives, because the plan's
 * grouping is a product decision and not something the schema carries.
 */
enum class ParamGroup(val title: String) {
    FilmPaper("Film & paper"),
    CameraExposure("Camera & exposure"),
    FrameFormat("Frame format"),
    Grain("Grain"),
    Halation("Halation"),
    Enlarger("Enlarger"),
    ScanOutput("Scan & output"),
    CropRotate("Crop & rotate"),
    Performance("Performance"),
}

enum class ParamKind { Slider, Toggle, Choice }

/**
 * Where a `str` field's accepted values come from. The engine validates these
 * by name at `set_params` time rather than by range, so the list has to come
 * from the resources the engine was built from (see [StockCatalog]).
 */
enum class ChoiceSource { Static, FilmStocks, PrintStocks, Illuminants }

data class ParamField(
    val wire: String,
    val group: ParamGroup,
    val label: String,
    val kind: ParamKind,
    val choices: List<String> = emptyList(),
    val choicesFrom: ChoiceSource = ChoiceSource.Static,
    /**
     * True for a field that only takes effect on the next `spk_open`
     * (`preview_long_edge`: the `live` tier's long edge, plan S5 group 8).
     */
    val nextOpenOnly: Boolean = false,
)

/**
 * Every wire name the UI may put in a delta, so a call site outside this file
 * can refer to a field by identity instead of by string. The parity test reads
 * the same names back out of [ParamsCatalog.fields].
 */
object Wire {
    const val FilmStock = "film_stock"
    const val PrintStock = "print_stock"
    const val DensityCurveGamma = "density_curve_gamma"
    const val InputColorSpace = "input_color_space"
    const val InputCctfDecoding = "input_cctf_decoding"

    const val ExposureCompensationEv = "exposure_compensation_ev"
    const val AutoExposure = "auto_exposure"
    const val AutoExposureMethod = "auto_exposure_method"
    const val LensBlurUm = "lens_blur_um"
    const val CameraFilter = "camera_filter"

    const val FilmFormatMm = "film_format_mm"

    const val GrainActive = "grain_active"
    const val GrainSublayersActive = "grain_sublayers_active"
    const val GrainAmount = "grain_amount"

    const val HalationActive = "halation_active"
    const val HalationAmount = "halation_amount"
    const val HalationBoostEv = "halation_boost_ev"
    const val HalationScatterAmount = "halation_scatter_amount"
    const val AntihalationRemoved = "antihalation_removed"
    const val DirCouplersActive = "dir_couplers_active"
    const val DirCouplersAmount = "dir_couplers_amount"

    const val PrintExposure = "print_exposure"
    const val MFilterShift = "m_filter_shift"
    const val YFilterShift = "y_filter_shift"
    const val FilterShiftScale = "filter_shift_scale"
    const val CFilterNeutral = "c_filter_neutral"
    const val MFilterNeutral = "m_filter_neutral"
    const val YFilterNeutral = "y_filter_neutral"
    const val PreflashExposure = "preflash_exposure"
    const val EnlargerIlluminant = "enlarger_illuminant"

    const val ScannerWhiteCorrection = "scanner_white_correction"
    const val ScannerBlackCorrection = "scanner_black_correction"
    const val ScannerLensBlur = "scanner_lens_blur"
    const val OutputColorSpace = "output_color_space"
    const val OutputCctfEncoding = "output_cctf_encoding"
    const val ScanFilm = "scan_film"
    const val ExtendedDynamicRange = "extended_dynamic_range"
    const val GlareActive = "glare_active"
    const val GlareAmount = "glare_amount"

    const val CropX = "geometry_crop_x"
    const val CropY = "geometry_crop_y"
    const val CropW = "geometry_crop_w"
    const val CropH = "geometry_crop_h"
    const val RotationDeg = "geometry_rotation_deg"
    const val QuarterTurns = "geometry_quarter_turns"
    const val FlipH = "geometry_flip_h"
    const val FlipV = "geometry_flip_v"

    const val PreviewLongEdge = "preview_long_edge"
}

object ParamsCatalog {

    /** The colour space names baked into `spektrafilm_constants.bin` (its `colour/cs/` keys). */
    val bakedColorSpaces = listOf(
        "sRGB", "Display P3", "ProPhoto RGB", "Adobe RGB (1998)",
        "ITU-R BT.709", "ITU-R BT.2020", "ACEScg", "ACES2065-1",
    )

    /**
     * `camera_filter`'s accepted names, from the engine's own rejection
     * message (`engine/src/core/params.cpp`, the `camera_filter` branch).
     */
    val cameraFilters = listOf("", "w8", "w11", "w12", "w15", "w21", "w25", "w47", "w58")

    /**
     * `auto_exposure_method`'s seven names: RFC-015 §2.3's four intents plus
     * the reference's three meters (`is_known_exposure_method`,
     * `engine/src/core/params.cpp`).
     */
    val exposureMethods = listOf(
        "balanced", "center", "protect_highlights", "protect_shadows",
        "center_weighted", "average", "median",
    )

    val fields: List<ParamField> = listOf(
        // --- film & paper ---------------------------------------------------
        ParamField(Wire.FilmStock, ParamGroup.FilmPaper, "Film stock", ParamKind.Choice, choicesFrom = ChoiceSource.FilmStocks),
        ParamField(Wire.PrintStock, ParamGroup.FilmPaper, "Print stock", ParamKind.Choice, choicesFrom = ChoiceSource.PrintStocks),
        ParamField(Wire.DensityCurveGamma, ParamGroup.FilmPaper, "Density curve gamma", ParamKind.Slider),
        ParamField(Wire.InputColorSpace, ParamGroup.FilmPaper, "Input colour space", ParamKind.Choice, choices = bakedColorSpaces),
        ParamField(Wire.InputCctfDecoding, ParamGroup.FilmPaper, "Input CCTF decoding", ParamKind.Toggle),

        // --- camera & exposure ---------------------------------------------
        ParamField(Wire.ExposureCompensationEv, ParamGroup.CameraExposure, "Exposure compensation", ParamKind.Slider),
        ParamField(Wire.AutoExposure, ParamGroup.CameraExposure, "Auto exposure", ParamKind.Toggle),
        ParamField(Wire.AutoExposureMethod, ParamGroup.CameraExposure, "Auto exposure method", ParamKind.Choice, choices = exposureMethods),
        ParamField(Wire.LensBlurUm, ParamGroup.CameraExposure, "Lens blur", ParamKind.Slider),
        ParamField(Wire.CameraFilter, ParamGroup.CameraExposure, "Camera filter", ParamKind.Choice, choices = cameraFilters),

        // --- frame format ---------------------------------------------------
        ParamField(Wire.FilmFormatMm, ParamGroup.FrameFormat, "Film format", ParamKind.Slider),

        // --- grain ----------------------------------------------------------
        ParamField(Wire.GrainActive, ParamGroup.Grain, "Grain", ParamKind.Toggle),
        ParamField(Wire.GrainSublayersActive, ParamGroup.Grain, "Grain sublayers", ParamKind.Toggle),
        ParamField(Wire.GrainAmount, ParamGroup.Grain, "Grain amount", ParamKind.Slider),

        // --- halation, and the DIR couplers that are its opposite ----------
        ParamField(Wire.HalationActive, ParamGroup.Halation, "Halation", ParamKind.Toggle),
        ParamField(Wire.HalationAmount, ParamGroup.Halation, "Halation amount", ParamKind.Slider),
        ParamField(Wire.HalationBoostEv, ParamGroup.Halation, "Halation boost", ParamKind.Slider),
        ParamField(Wire.HalationScatterAmount, ParamGroup.Halation, "Halation scatter", ParamKind.Slider),
        ParamField(Wire.AntihalationRemoved, ParamGroup.Halation, "Anti-halation layer removed", ParamKind.Toggle),
        ParamField(Wire.DirCouplersActive, ParamGroup.Halation, "DIR couplers", ParamKind.Toggle),
        ParamField(Wire.DirCouplersAmount, ParamGroup.Halation, "DIR coupler amount", ParamKind.Slider),

        // --- enlarger -------------------------------------------------------
        ParamField(Wire.PrintExposure, ParamGroup.Enlarger, "Print exposure", ParamKind.Slider),
        ParamField(Wire.MFilterShift, ParamGroup.Enlarger, "Magenta shift", ParamKind.Slider),
        ParamField(Wire.YFilterShift, ParamGroup.Enlarger, "Yellow shift", ParamKind.Slider),
        ParamField(Wire.FilterShiftScale, ParamGroup.Enlarger, "Filter shift scale (CC)", ParamKind.Slider),
        ParamField(Wire.CFilterNeutral, ParamGroup.Enlarger, "Cyan neutral", ParamKind.Slider),
        ParamField(Wire.MFilterNeutral, ParamGroup.Enlarger, "Magenta neutral", ParamKind.Slider),
        ParamField(Wire.YFilterNeutral, ParamGroup.Enlarger, "Yellow neutral", ParamKind.Slider),
        ParamField(Wire.PreflashExposure, ParamGroup.Enlarger, "Preflash", ParamKind.Slider),
        ParamField(Wire.EnlargerIlluminant, ParamGroup.Enlarger, "Enlarger illuminant", ParamKind.Choice, choicesFrom = ChoiceSource.Illuminants),

        // --- scan / output --------------------------------------------------
        ParamField(Wire.ScannerWhiteCorrection, ParamGroup.ScanOutput, "White correction", ParamKind.Toggle),
        ParamField(Wire.ScannerBlackCorrection, ParamGroup.ScanOutput, "Black correction", ParamKind.Toggle),
        ParamField(Wire.ScannerLensBlur, ParamGroup.ScanOutput, "Scanner lens blur", ParamKind.Slider),
        ParamField(Wire.OutputColorSpace, ParamGroup.ScanOutput, "Output colour space", ParamKind.Choice, choices = bakedColorSpaces),
        ParamField(Wire.OutputCctfEncoding, ParamGroup.ScanOutput, "Output CCTF encoding", ParamKind.Toggle),
        ParamField(Wire.ScanFilm, ParamGroup.ScanOutput, "Scan the film (bypass paper)", ParamKind.Toggle),
        ParamField(Wire.ExtendedDynamicRange, ParamGroup.ScanOutput, "Extended dynamic range", ParamKind.Toggle),
        ParamField(Wire.GlareActive, ParamGroup.ScanOutput, "Print glare", ParamKind.Toggle),
        ParamField(Wire.GlareAmount, ParamGroup.ScanOutput, "Glare amount", ParamKind.Slider),

        // --- geometry (the crop page owns the drag; these are its numbers) --
        ParamField(Wire.CropX, ParamGroup.CropRotate, "Crop x", ParamKind.Slider),
        ParamField(Wire.CropY, ParamGroup.CropRotate, "Crop y", ParamKind.Slider),
        ParamField(Wire.CropW, ParamGroup.CropRotate, "Crop width", ParamKind.Slider),
        ParamField(Wire.CropH, ParamGroup.CropRotate, "Crop height", ParamKind.Slider),
        ParamField(Wire.RotationDeg, ParamGroup.CropRotate, "Rotation", ParamKind.Slider),
        ParamField(Wire.QuarterTurns, ParamGroup.CropRotate, "Quarter turns", ParamKind.Slider),
        ParamField(Wire.FlipH, ParamGroup.CropRotate, "Flip horizontal", ParamKind.Toggle),
        ParamField(Wire.FlipV, ParamGroup.CropRotate, "Flip vertical", ParamKind.Toggle),

        // --- performance ----------------------------------------------------
        ParamField(Wire.PreviewLongEdge, ParamGroup.Performance, "Canvas resolution", ParamKind.Slider, nextOpenOnly = true),
    )

    /** Every wire name the UI can put in a delta — what the parity test checks. */
    val wireNames: Set<String> = fields.map { it.wire }.toSet()

    /** The groups the parameter sheet offers, in the plan's order. */
    val sheetGroups: List<ParamGroup> = listOf(
        ParamGroup.FilmPaper,
        ParamGroup.CameraExposure,
        ParamGroup.FrameFormat,
        ParamGroup.Grain,
        ParamGroup.Halation,
        ParamGroup.Enlarger,
        ParamGroup.ScanOutput,
        ParamGroup.Performance,
    )

    fun fieldsOf(group: ParamGroup): List<ParamField> = fields.filter { it.group == group }

    fun field(wire: String): ParamField? = fields.firstOrNull { it.wire == wire }

    /**
     * The `unsupported_features` family a wire name belongs to, or null.
     *
     * The engine refuses these by name and plan D6 says the UI hides them; a
     * family is matched by prefix because each refused feature is a group of
     * fields (`overscan_*`, `date_imprint_*`, `scene_latitude*`,
     * `contrast_mask_*`, `digital_intermediate*`). This is the guard that makes
     * a later catalogue addition of, say, `overscan_fog` a *test failure*
     * rather than a silently refused slider.
     */
    fun familyOf(wire: String): String? = when {
        wire.startsWith("overscan_") -> "overscan"
        wire.startsWith("date_imprint_") -> "date_imprint"
        wire.startsWith("scene_latitude") -> "scene_latitude_mapping"
        wire.startsWith("contrast_mask_") -> "contrast_mask"
        wire.startsWith("digital_intermediate") -> "digital_intermediate"
        else -> null
    }

    /** The five families the non-Apple backend refuses (`engine.cpp:320-325`). */
    val refusedFamilies: Set<String> = setOf(
        "digital_intermediate", "scene_latitude_mapping", "contrast_mask", "overscan", "date_imprint",
    )

    /**
     * The fields worth showing on this device: the schema has to know the name
     * (an engine that dropped a field must not get it sent), and the field's
     * family must not be one the backend refuses.
     */
    fun visibleFields(schema: SchemaView, unsupportedFeatures: Set<String>): List<ParamField> =
        fields.filter { field ->
            schema.has(field.wire) && (familyOf(field.wire)?.let { it !in unsupportedFeatures } ?: true)
        }
}

/**
 * A read-only view of the `spk_params_schema` reply: `{"schema_version":1,
 * "fields":[{"name","path","type","layer","live","default","range":?}]}`.
 *
 * Sliders take their bounds from here and nowhere else (`engine/src/core/
 * params.cpp`'s `kFields` is the authority, and this is its wire form).
 */
class SchemaView(schema: JSONObject?) {

    private val byName: Map<String, JSONObject> = buildMap {
        val array = schema?.optJSONArray("fields") ?: return@buildMap
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val name = entry.optString("name")
            if (name.isNotEmpty()) put(name, entry)
        }
    }

    val size: Int get() = byName.size

    fun has(wire: String): Boolean = byName.containsKey(wire)

    fun type(wire: String): String = byName[wire]?.optString("type").orEmpty()

    /** The schema's own `range`, or null for a field the schema does not bound. */
    fun range(wire: String): ClosedFloatingPointRange<Float>? {
        val array = byName[wire]?.optJSONArray("range") ?: return null
        if (array.length() < 2) return null
        return array.optDouble(0).toFloat()..array.optDouble(1).toFloat()
    }

    fun isInteger(wire: String): Boolean = type(wire) == "int"

    fun isNumeric(wire: String): Boolean = type(wire) == "float" || type(wire) == "int"

    /**
     * The schema's `live`: a field this flag is false for cannot be written
     * onto a running pipeline, so changing it makes the engine rebuild (or, for
     * a shoot-layer field, re-develop the negative). The UI tags those
     * controls with that cost instead of hiding it.
     */
    fun isLive(wire: String): Boolean = byName[wire]?.optBoolean("live", false) ?: false

    fun defaultBoolean(wire: String): Boolean = byName[wire]?.optBoolean("default", false) ?: false

    fun defaultString(wire: String): String = byName[wire]?.optString("default").orEmpty()

    /** The current value, falling back to the schema's default. */
    fun currentOf(params: JSONObject?, wire: String): Any? {
        val entry = byName[wire]
        val fallback: Any? = when (type(wire)) {
            "bool" -> defaultBoolean(wire)
            "float", "int" -> entry?.optDouble("default", 0.0)
            else -> defaultString(wire)
        }
        val params0 = params ?: return fallback
        if (!params0.has(wire)) return fallback
        return when (type(wire)) {
            "bool" -> params0.optBoolean(wire, fallback as? Boolean ?: false)
            "float", "int" -> params0.optDouble(wire, (fallback as? Double) ?: 0.0)
            else -> params0.optString(wire, fallback as? String ?: "")
        }
    }
}

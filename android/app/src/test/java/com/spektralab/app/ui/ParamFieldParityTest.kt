package com.spektralab.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plan's S5 rule, as a test: **the UI cannot name a field the engine does
 * not have, and cannot offer a control for a feature this backend refuses.**
 *
 * Both fixtures are captured engine replies, not hand-written lists:
 *
 *  * `params_schema.json` is an `spk_params_schema` reply (98 fields);
 *  * `capabilities.json` is an `spk_capabilities` reply from the same build,
 *    carrying `backend.unsupported_features`.
 *
 * So this test fails the moment the UI adds a slider for a field upstream
 * renamed or dropped, and it fails when the UI adds a control belonging to one
 * of the five features the non-Apple backend refuses by name
 * (`engine/src/pipeline/engine.cpp:320-325`, plan D6) — which is exactly the
 * failure mode the plan wants at build time rather than at the user's
 * `set_params`.
 */
class ParamFieldParityTest {

    private val schema = TestJson.parse(TestJson.resource("/params_schema.json")).asMap()
    private val capabilities = TestJson.parse(TestJson.resource("/capabilities.json")).asMap()

    private val fields: List<Map<String, Any?>> = schema["fields"].asList().map { it.asMap() }
    private val byName: Map<String, Map<String, Any?>> = fields.associateBy { it["name"].asString() }
    private val unsupported: Set<String> =
        capabilities["backend"].asMap()["unsupported_features"].asList().map { it.asString() }.toSet()

    @Test
    fun fixtureIsTheEnginesOwnSchema() {
        assertEquals("schema_version", 1.0, schema["schema_version"].asDouble(), 0.0)
        assertTrue("the captured schema looks truncated: ${fields.size} fields", fields.size > 90)
        assertEquals(
            setOf("live", "preview", "full"),
            capabilities["tiers"].asMap().keys,
        )
    }

    /** (a) every wire name the UI can send exists in the schema. */
    @Test
    fun everyCatalogueFieldIsDeclaredByTheEngine() {
        val unknown = ParamsCatalog.wireNames.filterNot { byName.containsKey(it) }
        assertTrue(
            "the UI's catalogue names fields this engine's schema does not have: $unknown " +
                "(fix the catalogue, never the fixture — the fixture is the engine)",
            unknown.isEmpty(),
        )
    }

    @Test
    fun wireNamesAreDeclaredOnce() {
        val duplicates = ParamsCatalog.fields.groupBy { it.wire }.filterValues { it.size > 1 }.keys
        assertTrue("catalogue repeats a wire name: $duplicates", duplicates.isEmpty())
        assertEquals(
            "a ParamField and the Wire constant disagree",
            ParamsCatalog.wireNames.size,
            ParamsCatalog.fields.size,
        )
    }

    /** (b) the UI's field set does not intersect the refused features. */
    @Test
    fun noCatalogueFieldBelongsToARefusedFeature() {
        val offenders = ParamsCatalog.fields.map { it.wire }.filter { wire ->
            wire in unsupported || ParamsCatalog.familyOf(wire)?.let { it in unsupported } == true
        }
        assertTrue(
            "plan D6 hides these features rather than disabling them, but the catalogue offers: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun theFamilyMapCoversEveryRefusedFeature() {
        assertEquals(
            "the engine refuses a feature the UI's family map does not know how to hide",
            unsupported,
            ParamsCatalog.refusedFamilies,
        )
        // And the mapping is real, not a set of names that never matches.
        assertEquals("overscan", ParamsCatalog.familyOf("overscan_active"))
        assertEquals("date_imprint", ParamsCatalog.familyOf("date_imprint_active"))
        assertEquals("scene_latitude_mapping", ParamsCatalog.familyOf("scene_latitude_active"))
        assertEquals("contrast_mask", ParamsCatalog.familyOf("contrast_mask_active"))
        assertEquals("digital_intermediate", ParamsCatalog.familyOf("digital_intermediate"))
        // The refused families' own fields are in the schema and out of the catalogue.
        for (refused in listOf("overscan_active", "date_imprint_active", "contrast_mask_active")) {
            assertTrue("$refused should be a real schema field", byName.containsKey(refused))
            assertTrue("$refused must not be in the catalogue", refused !in ParamsCatalog.wireNames)
        }
    }

    /**
     * (c) every slider is bounded by the schema's own `range`.
     *
     * `ParamField` carries no bounds at all — that is the structural half of
     * the rule (there is nothing to write a range into) — so what is checked
     * here is that the schema can answer for every slider the UI builds. A
     * numeric field without a `range` renders read-only in the UI rather than
     * getting an invented one.
     */
    @Test
    fun slidersAreBoundedByTheSchemaRange() {
        for (field in ParamsCatalog.fields) {
            val entry = byName.getValue(field.wire)
            when (field.kind) {
                ParamKind.Slider -> {
                    assertTrue(
                        "${field.wire} is a slider but the schema says type '${entry["type"]}'",
                        entry["type"].asString() in setOf("float", "int"),
                    )
                    val range = entry["range"]
                    assertTrue(
                        "${field.wire} is a slider but the schema declares no range, so the UI has no bounds to use",
                        range != null,
                    )
                    val bounds = range!!.asList().map { it.asDouble() }
                    assertEquals("${field.wire} needs exactly two bounds", 2, bounds.size)
                    assertTrue("${field.wire} has an inverted range $bounds", bounds[0] < bounds[1])
                }
                ParamKind.Toggle -> assertEquals(
                    "${field.wire} is a toggle but the schema says '${entry["type"]}'",
                    "bool",
                    entry["type"].asString(),
                )
                ParamKind.Choice -> assertEquals(
                    "${field.wire} is a choice but the schema says '${entry["type"]}'",
                    "str",
                    entry["type"].asString(),
                )
            }
        }
    }

    /** The one field that is not a `setParams` field is the one the open uses. */
    @Test
    fun previewLongEdgeIsTheOnlyNextOpenField() {
        val nextOpen = ParamsCatalog.fields.filter { it.nextOpenOnly }
        assertEquals(listOf(Wire.PreviewLongEdge), nextOpen.map { it.wire })
        val entry = byName.getValue(Wire.PreviewLongEdge)
        assertEquals("int", entry["type"].asString())
        assertEquals(listOf(800.0, 8192.0), entry["range"].asList().map { it.asDouble() })
        // EditorViewModel.setPreviewLongEdge clamps to exactly this range.
        val clamped = ParamsCatalog.fields.first { it.nextOpenOnly }
        assertEquals(Wire.PreviewLongEdge, clamped.wire)
    }

    /**
     * The crop page's own minimum, checked against the schema: the UI may not
     * squeeze a crop rectangle below anything the schema would refuse.
     */
    @Test
    fun cropMinimumStaysInsideTheSchemaRange() {
        val minCrop = Geometry.MIN_CROP
        for (wire in listOf(Wire.CropW, Wire.CropH)) {
            val bounds = byName.getValue(wire)["range"].asList().map { it.asDouble() }
            assertTrue("$wire minimum ${bounds[0]} is above the UI's $minCrop", bounds[0] <= minCrop)
            assertTrue("$wire maximum ${bounds[1]} is below the UI's $minCrop", bounds[1] >= minCrop)
        }
    }

    /**
     * The crop page iterates its catalogue group in order and the drag writes
     * those names, so the group's order is part of the contract with the
     * engine's own field order.
     */
    @Test
    fun cropGroupMatchesTheSchemaOrder() {
        val declared = fields.map { it["name"].asString() }.filter { it.startsWith("geometry_") }
        assertEquals(declared, ParamsCatalog.fieldsOf(ParamGroup.CropRotate).map { it.wire })
    }
}

package com.spektralab.app.ui

import com.spektralab.app.ui.screens.params.ParamsFields
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
 * So this test fails the moment a screen adds a control for a field upstream
 * renamed or dropped, and it fails when one adds a control belonging to one of
 * the five features the non-Apple backend refuses by name
 * (`engine/src/pipeline/engine.cpp:320-325`, plan D6).
 *
 * It used to check a `ParamsCatalog` object that no longer exists — the sheet it
 * described was replaced by full pages. The list it checks now is
 * [ParamsFields], which names the fields those pages actually write, so the
 * guarantee survived the screen it was written for.
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

    /** (a) every wire name a page can send exists in the schema. */
    @Test
    fun everyPageFieldIsDeclaredByTheEngine() {
        val unknown = ParamsFields.leaves.filterNot { byName.containsKey(it) }
        assertTrue(
            "the pages name fields this engine's schema does not have: $unknown " +
                "(fix the page, never the fixture — the fixture is the engine)",
            unknown.isEmpty(),
        )
    }

    @Test
    fun wireNamesAreDeclaredOnce() {
        val repeated = ParamsFields.wireNames.groupBy { ParamsFields.leaf(it) }
            .filterValues { it.size > 1 }
            .keys
        assertTrue("the same engine field is written from two rows: $repeated", repeated.isEmpty())
        assertEquals(ParamsFields.wireNames.size, ParamsFields.leaves.size)
    }

    /** (b) no field the pages write belongs to a refused feature. */
    @Test
    fun noPageFieldBelongsToARefusedFeature() {
        val refusedFields = ParamsFields.refusedFamilies.values.flatten().toSet()
        val offenders = ParamsFields.leaves.filter { it in refusedFields }
        assertTrue(
            "these belong to a feature this backend refuses, so the pages must not write them: $offenders",
            offenders.isEmpty(),
        )
    }

    /**
     * Every refused family is real: the engine names it, and the schema carries
     * the fields, which is what makes "shown but disabled with a reason" the
     * honest treatment rather than a missing control.
     */
    @Test
    fun everyRefusedFamilyIsNamedByTheEngine() {
        assertEquals(
            "the engine refuses a feature this map does not know how to show as refused",
            unsupported,
            ParamsFields.refusedFamilies.keys,
        )
        for ((family, names) in ParamsFields.refusedFamilies) {
            for (name in names) {
                assertTrue("$family names $name, which the schema does not have", byName.containsKey(name))
            }
        }
    }

    /**
     * (c) every slider the pages write is bounded by the schema's own `range`.
     *
     * A numeric field without a range would render read-only rather than getting
     * an invented one, so this checks the schema can answer for each of them.
     */
    @Test
    fun slidersAreBoundedByTheSchemaRange() {
        val sliders = listOf(
            "exposure_compensation_ev",
            "film_format_mm",
            "print_exposure",
            "density_curve_gamma",
            "contrast_mask_highlights",
            "y_filter_neutral",
            "m_filter_neutral",
        )
        for (wire in sliders) {
            val entry = byName.getValue(wire)
            assertTrue("$wire should be numeric", entry["type"].asString() in setOf("float", "int"))
            val range = entry["range"]
            assertTrue("$wire is a slider but the schema declares no range", range != null)
            val bounds = range!!.asList().map { it.asDouble() }
            assertEquals("$wire needs exactly two bounds", 2, bounds.size)
            assertTrue("$wire has an inverted range $bounds", bounds[0] < bounds[1])
        }
    }

    /** The toggles the page writes are booleans, not numbers. */
    @Test
    fun togglesAreBooleansInTheSchema() {
        for (wire in listOf("grain_active", "halation_active")) {
            assertEquals("$wire", "bool", byName.getValue(wire)["type"].asString())
        }
    }

    /** The choice control writes a string the schema calls `str`. */
    @Test
    fun theMeteringChoiceIsAStringField() {
        assertEquals("auto_exposure_method", "str", byName.getValue("auto_exposure_method")["type"].asString())
    }
}

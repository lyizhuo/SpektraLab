package com.spektralab.app.ui

import com.spektralab.app.ui.screens.params.ParamsFields
import com.spektralab.app.ui.screens.params.RefusedSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "unavailable, and here is why" rule for the five refused features.
 *
 * Two things are pinned, and both have been wrong before in other forms:
 *
 * 1. **Every refused family has a sentence.** A family the engine refuses but
 *    the app does not name is a hole: the prototype draws 片边 and the app shows
 *    nothing, with no explanation.
 * 2. **The list is the engine's, not the platform's.** It has to match
 *    `backend.unsupported_features` exactly — hard-coding "this platform cannot
 *    do film edges" would survive the day the port lands and tell the user a lie
 *    in the other direction.
 *
 * This used to check a `ParamsCatalog` object that no longer exists; the screen
 * that replaced it reads [RefusedSections], so the rule now has a subject again.
 */
class RefusedSectionsTest {

    private val capabilities = TestJson.parse(TestJson.resource("/capabilities.json")).asMap()
    private val unsupported: Set<String> =
        capabilities["backend"].asMap()["unsupported_features"].asList().map { it.asString() }.toSet()

    @Test
    fun everyFamilyTheEngineRefusesHasASentence() {
        assertEquals(
            "a family the engine refuses has no explanation anywhere in the app",
            unsupported,
            RefusedSections.families.toSet(),
        )
    }

    @Test
    fun eachSectionSaysWhatWillNotHappen() {
        for (family in RefusedSections.families) {
            val section = RefusedSections.of(family)!!
            assertTrue("$family has no label", section.label.isNotBlank())
            // A reason is a sentence about the picture, not a status code.
            assertTrue(
                "$family's detail is too short to explain anything: ${section.detail}",
                section.detail.length > 16,
            )
        }
    }

    @Test
    fun aFamilyTheEngineAcceptsHasNoSentence() {
        // Grain and halation work on every backend. Printing "颗粒不可用" under a
        // working switch is the failure this asserts against.
        assertNull(RefusedSections.explain("grain"))
        assertNull(RefusedSections.explain("halation"))
        assertNull(RefusedSections.of(""))
    }

    @Test
    fun theOrderIsTheDocumentsOrderNotTheSetsOrder() {
        // Film edge, date back, scene placement, contrast mask, then DI: the
        // order the design notes introduce them, so the panel does not reshuffle
        // itself between two devices that refuse the same set.
        assertEquals(
            listOf("overscan", "date_imprint", "scene_latitude_mapping", "contrast_mask", "digital_intermediate"),
            RefusedSections.families,
        )
    }

    @Test
    fun aRefusedFeatureIsNeverAlsoWrittenByAPage() {
        // The two halves have to agree: it would be nonsense to print "场景置位
        // 不可用" directly above a working latitude slider.
        val refused = ParamsFields.refusedFamilies.values.flatten().toSet()
        val written = ParamsFields.leaves.toSet()
        assertTrue(
            "a page writes a field belonging to a refused feature: ${written intersect refused}",
            (written intersect refused).isEmpty(),
        )
    }
}

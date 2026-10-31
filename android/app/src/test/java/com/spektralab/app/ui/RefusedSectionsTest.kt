package com.spektralab.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "unavailable, and here is why" rule for the five refused sections.
 *
 * Two things are pinned here, and both have been wrong before in this
 * repository's history in other forms:
 *
 * 1. **Every refused family has a section.** A family the engine refuses but
 *    the panel does not name is a hole — the user sees 片边 in the design and
 *    nothing in the app, with no explanation.
 * 2. **The sections are driven by the device's capabilities, not by the
 *    platform.** On Metal these must all be absent, because hard-coding "this
 *    platform cannot do film edges" would survive the day the port lands and
 *    tell the user a lie in the other direction.
 */
class RefusedSectionsTest {

    @Test
    fun everyFamilyTheCatalogueRefusesHasASection() {
        val sections = ParamsCatalog.refusedSections(ParamsCatalog.refusedFamilies)
        val covered = sections.map { it.family }.toSet()

        assertEquals(
            "a family the engine refuses has no explanation in the panel",
            ParamsCatalog.refusedFamilies,
            covered,
        )
    }

    @Test
    fun eachSectionSaysWhatWillNotHappen() {
        for (section in ParamsCatalog.refusedSections(ParamsCatalog.refusedFamilies)) {
            assertTrue("${section.family} has no title", section.title.isNotBlank())
            assertTrue("${section.family} has no detail", section.detail.isNotBlank())
            // A reason is a sentence about the picture, not a status code.
            assertTrue(
                "${section.family}'s detail is too short to explain anything: ${section.detail}",
                section.detail.length > 12,
            )
        }
    }

    @Test
    fun aBackendThatSupportsEverythingGetsNoSections() {
        // Metal refuses none of the five. The panel must then show nothing,
        // which is what makes this a capability rule rather than a platform
        // assumption.
        assertEquals(emptyList<ParamsCatalog.RefusedSection>(), ParamsCatalog.refusedSections(emptySet()))
    }

    @Test
    fun onlyTheRefusedFamiliesAreReported() {
        val sections = ParamsCatalog.refusedSections(setOf("overscan", "grain", "halation"))
        assertEquals(listOf("overscan"), sections.map { it.family })
    }

    @Test
    fun theOrderIsTheDocumentsOrderNotTheSetsOrder() {
        // Film edge, then date back, then scene placement: the order the design
        // notes introduce them, so the panel does not reshuffle itself between
        // two devices that refuse the same set.
        val sections = ParamsCatalog.refusedSections(ParamsCatalog.refusedFamilies)
        assertEquals(
            listOf("overscan", "date_imprint", "scene_latitude_mapping", "contrast_mask", "digital_intermediate"),
            sections.map { it.family },
        )
    }

    @Test
    fun aRefusedSectionNeverDuplicatesAFieldThatIsStillVisible() {
        // The two halves have to agree: it would be nonsense to print "片边 is
        // unavailable on this platform" directly above a working overscan
        // slider.
        val schema = SchemaView(null)
        val unsupported = ParamsCatalog.refusedFamilies
        val visible = ParamsCatalog.visibleFields(schema, unsupported).map { it.wire }.toSet()

        for (section in ParamsCatalog.refusedSections(unsupported)) {
            for (wire in visible) {
                assertEquals(
                    "$wire is visible but its section says ${section.family} is unavailable",
                    null,
                    if (ParamsCatalog.familyOf(wire) == section.family) wire else null,
                )
            }
        }
    }
}

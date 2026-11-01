package com.spektralab.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spektralab.app.ui.screens.params.ParamsFields
import com.spektralab.app.ui.screens.params.ParamsScreen
import com.spektralab.app.ui.screens.params.RefusedSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A refused feature, drawn for real.
 *
 * `RefusedSectionsTest` pins *which* families are refused and what the sentence
 * says; this pins that a person can see it on the page that would otherwise have
 * the control — 场景置位 sits in place, disabled, with its reason under it, and
 * its two handles do nothing.
 *
 * It used to compose a `RefusedSections` notice block that the parameter page no
 * longer has: the replacement draws the refusal inline, on the rows themselves,
 * which is where the prototype puts them.
 *
 * No engine and no photograph are involved — the page renders from a null params
 * document, which is the state a frame is in before its first reply.
 */
@RunWith(AndroidJUnit4::class)
class RefusedSectionsComposeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theRefusedFamilyIsOnThePageAndSaysWhy() {
        compose.setContent {
            ParamsScreen(params = null, sourceName = "fixture.png", onDelta = {}, onBack = {})
        }

        compose.onNodeWithText(RefusedSections.label("scene_latitude_mapping")).performScrollTo().assertIsDisplayed()

        // The handles are the refused part, and they are disabled in place.
        compose.onNodeWithTag("高光回拉-disabled").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("阴影回拉-disabled").assertIsDisplayed()

        // The sentence is on screen, not only in the data.
        val mentions = compose.onAllNodesWithText("本平台不支持应用", substring = true)
            .fetchSemanticsNodes()
        assertTrue("the page does not say why 场景置位 is off", mentions.isNotEmpty())
    }

    @Test
    fun thePageNeverWritesARefusedField() {
        // The data half and the screen half have to agree: a visible row that
        // wrote a refused field would be a control the backend cannot honour.
        val refused = ParamsFields.refusedFamilies.values.flatten().toSet()
        val written = ParamsFields.leaves.toSet()
        assertEquals(emptySet<String>(), written intersect refused)
    }

    @Test
    fun theOtherFaceOfThePageStillWorks() {
        // 显影后 must remain reachable: disabling one family is not a reason to
        // lose the four that work.
        compose.setContent {
            ParamsScreen(params = null, sourceName = "fixture.png", onDelta = {}, onBack = {})
        }
        compose.onNodeWithTag("params-after").performClick()
        compose.onNodeWithTag("field-print_exposure").performScrollTo().assertIsDisplayed()
    }
}

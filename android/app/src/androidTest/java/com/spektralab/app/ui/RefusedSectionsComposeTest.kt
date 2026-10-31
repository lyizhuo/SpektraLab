package com.spektralab.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The unavailable-section notice, drawn for real.
 *
 * `RefusedSectionsTest` pins which sections exist and what they say; this pins
 * that a person can actually see it. Both are needed: the data test would pass
 * with the composable wired to nothing, and this one would pass with the wrong
 * text.
 *
 * No engine and no photograph are involved — the notice is the one part of the
 * parameter panel that renders on a device where the backend refuses the stage
 * outright, which is exactly the device this app runs on.
 */
@RunWith(AndroidJUnit4::class)
class RefusedSectionsComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private val sections = ParamsCatalog.refusedSections(ParamsCatalog.refusedFamilies)

    @Test
    fun everyUnavailableSectionIsOnScreen() {
        compose.setContent { RefusedSections(sections) }

        for (section in sections) {
            compose.onNodeWithTag("refused-${section.family}").assertIsDisplayed()
        }
    }

    @Test
    fun theSectionShowsItsTitleAndItsReason() {
        compose.setContent { RefusedSections(sections) }

        val edge = sections.first { it.family == "overscan" }
        compose.onNodeWithText(edge.title).assertIsDisplayed()
        compose.onNodeWithText(edge.detail).assertIsDisplayed()
        // ...and the badge that says it is an unavailable capability rather
        // than a missing control. One per section, hence a count rather than a
        // single-node lookup.
        val badges = compose.onAllNodesWithText("本平台不可用", useUnmergedTree = true)
            .fetchSemanticsNodes()
        check(badges.size == sections.size) {
            "expected ${sections.size} badges, found ${badges.size}"
        }
    }

    @Test
    fun theReasonIsReachableAsAccessibilityTextNotOnlyAsColour() {
        // A greyed row whose explanation exists only as a colour is not an
        // explanation. The content description is what makes it one for a
        // screen reader, and what makes it assertable here.
        compose.setContent { RefusedSections(sections) }

        val date = sections.first { it.family == "date_imprint" }
        compose.onNodeWithContentDescription("${date.title}：${date.detail}").assertIsDisplayed()
    }

    @Test
    fun anEmptyListDrawsNothing() {
        // The day the Vulkan port lands, `unsupported_features` will not contain
        // these families and the panel must gain controls where a notice was.
        compose.setContent { RefusedSections(emptyList()) }
        compose.onNodeWithTag("refused-overscan").assertDoesNotExist()
    }
}

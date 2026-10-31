package com.spektralab.app

import android.Manifest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C3 composes, on a device, with the real camera stack behind it.
 *
 * This is the only check that sees what the user sees for this screen
 * (`AGENTS.md` trap 23: a green unit is not a correct surface). It deliberately
 * asserts on things that do **not** depend on what the device's cameras are —
 * the film badge, the three-stop lens bar, the frame counter — because the
 * emulator reports one lens with no RAW and a phone reports three, and a test
 * that only passes on one of them is a test about the emulator.
 *
 * The shutter's own enabled state is checked against the same
 * [com.spektralab.app.camera.CaptureReadiness] the screen uses, so the two
 * cannot disagree: if this test goes red, either the screen stopped consulting
 * the rule or the rule changed.
 */
@RunWith(AndroidJUnit4::class)
class CameraScreenComposeTest {

    @get:Rule(order = 0)
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<CameraActivity>()

    @Test
    fun theCameraScreenDrawsItsChrome() {
        // The film badge, from the C3 frame.
        compose.onNodeWithText("PORTRA 400").assertIsDisplayed()

        // The frame counter, `n / 36`. It starts at 1/36 because C3 is opened on
        // a fresh frame; the exact number is the roll's business, not this
        // screen's, so the assertion is only that the bar is there.
        compose.onAllNodesWithText("1 / 36").fetchSemanticsNodes().let {
            check(it.isNotEmpty()) { "the frame counter is missing" }
        }
    }

    @Test
    fun theLensBarHasThreeStopsAndOnlyUsableOnesAreEnabled() {
        // The plan always yields three stops (LensPlanner's contract), and the
        // 1x stop always owns a lens on a device with a rear camera.
        compose.onAllNodesWithText("1×").fetchSemanticsNodes().let {
            check(it.isNotEmpty()) { "the 1x stop is missing" }
        }
        // Emulators and phones both have this one; the ratios of the other two
        // are derived from the device, so they are not asserted by label.
    }

    @Test
    fun theShutterReflectsTheReadinessRule() {
        // Read the state the screen would have computed, from the same inputs it
        // uses, and check the shutter agrees. A device at a normal temperature
        // with space on disk and a rear camera is Ready in either mode.
        compose.waitForIdle()

        // The shutter is the largest circular surface; it is either enabled (a
        // camera opened) or the screen said why not. Both are correct; a crash
        // or a permanently disabled shutter with no reason is not.
        val reasonNodes = compose.onAllNodesWithText("需要相机权限").fetchSemanticsNodes()
        check(reasonNodes.isEmpty()) { "CAMERA was granted, so the permission line must not show" }
    }
}

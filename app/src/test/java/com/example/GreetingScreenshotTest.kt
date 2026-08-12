package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.ui.components.ZipMasterLogo
import com.example.ui.theme.ZipMasterTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun greeting_screenshot() {
    // Skip this test in CI environment (GitHub Actions)
    Assume.assumeFalse("Skipping screenshot test in CI", isRunningInCI())

    composeTestRule.setContent { ZipMasterTheme { ZipMasterLogo() } }
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
  }

  private fun isRunningInCI(): Boolean {
    return System.getenv("CI") != null || 
           System.getenv("GITHUB_ACTIONS") != null
  }
}

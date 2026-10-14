package io.wutz.apollo.ui.landing

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import io.wutz.apollo.AuthLoginScreen
import io.wutz.apollo.ChatScreen
import io.wutz.apollo.ConnectionsScreen
import io.wutz.apollo.LandingScreen
import io.wutz.apollo.NavigationController
import io.wutz.apollo.PairingScreen
import io.wutz.apollo.R
import io.wutz.apollo.theme.HermesControlTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * First-run navigation: the real Landing screen wired like `Navigation.kt` (every button goes through
 * [NavigationController]) inside a real Navigation 3 back stack, at a phone and a tablet window size.
 * The destinations are placeholders: this checks routing, double-tap dedup and back behaviour, not
 * the Connections, Pairing or login screens themselves.
 */
@RunWith(Parameterized::class)
@MediumTest
class LandingPairingFlowTest(
    private val windowName: String,
    // Plain dp values: JUnit's Parameterized runner cannot pass the inline DpSize class to the constructor.
    private val widthDp: Int,
    private val heightDp: Int,
) {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private lateinit var backStack: NavBackStack<NavKey>

    @After
    fun tearDown() {
        NavigationController.backStack = null
    }

    private fun setFlow() {
        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp))) {
                HermesControlTheme {
                    val stack = rememberNavBackStack(LandingScreen)
                    SideEffect {
                        NavigationController.backStack = stack
                        backStack = stack
                    }
                    NavDisplay(
                        backStack = stack,
                        onBack = { NavigationController.goBack() },
                        entryProvider =
                            entryProvider {
                                entry<LandingScreen> {
                                    LandingScreen(
                                        onConnections = { NavigationController.navigateTo(ConnectionsScreen) },
                                        onAuthLogin = { NavigationController.navigateTo(AuthLoginScreen) },
                                    )
                                }
                                entry<ConnectionsScreen> { Placeholder("screen_connections") }
                                entry<PairingScreen> { Placeholder("screen_pairing") }
                                entry<AuthLoginScreen> { Placeholder("screen_auth_login") }
                                entry<ChatScreen> { Placeholder("screen_chat") }
                            },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    /** Two taps before the next frame, as a fast double tap delivers them. */
    private fun SemanticsNodeInteraction.doubleTapBeforeNextFrame() {
        composeTestRule.mainClock.autoAdvance = false
        performClick()
        performClick()
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()
    }

    private fun landingButton(textRes: Int) =
        composeTestRule.onNodeWithText(resources.getString(textRes)).performScrollTo()

    @Test
    fun connectionsDoubleTapOpensOneEntryAndBackReturnsToLanding() {
        setFlow()

        landingButton(R.string.screen_connections).doubleTapBeforeNextFrame()

        composeTestRule.onNodeWithTag("screen_connections").assertIsDisplayed()
        assertEquals(listOf<NavKey>(LandingScreen, ConnectionsScreen), backStack.toList())

        composeTestRule.runOnIdle { NavigationController.goBack() }
        landingButton(R.string.landing_action_auth_login).assertIsDisplayed()
        assertEquals(listOf<NavKey>(LandingScreen), backStack.toList())
    }

    @Test
    fun pairingFromConnectionsKeepsLandingAsRoot() {
        setFlow()

        landingButton(R.string.screen_connections).performClick()
        composeTestRule.runOnIdle { NavigationController.navigateTo(PairingScreen) }

        composeTestRule.onNodeWithTag("screen_pairing").assertIsDisplayed()
        // Before the first login a primary screen replaces the previous one but keeps Landing as root.
        assertEquals(listOf<NavKey>(LandingScreen, PairingScreen), backStack.toList())

        composeTestRule.runOnIdle { NavigationController.goBack() }
        assertEquals(listOf<NavKey>(LandingScreen), backStack.toList())
    }

    @Test
    fun signInDoubleTapOpensOneLoginEntryAndLoginResetsToChat() {
        setFlow()

        landingButton(R.string.landing_action_auth_login).doubleTapBeforeNextFrame()

        composeTestRule.onNodeWithTag("screen_auth_login").assertIsDisplayed()
        assertEquals(listOf<NavKey>(LandingScreen, AuthLoginScreen), backStack.toList())

        // What AuthLoginScreen's onConnected does in Navigation.kt.
        composeTestRule.runOnIdle { NavigationController.resetTo(ChatScreen) }
        composeTestRule.onNodeWithTag("screen_chat").assertIsDisplayed()
        assertEquals(listOf<NavKey>(ChatScreen), backStack.toList())

        // Back on the root chat must not bring Landing back or empty the stack.
        composeTestRule.runOnIdle { NavigationController.goBack() }
        assertEquals(listOf<NavKey>(ChatScreen), backStack.toList())
    }

    @Test
    fun backOnLandingAloneKeepsLanding() {
        setFlow()

        composeTestRule.runOnIdle { NavigationController.goBack() }

        landingButton(R.string.screen_connections).assertIsDisplayed()
        assertEquals(listOf<NavKey>(LandingScreen), backStack.toList())
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun windows(): List<Array<Any>> =
            listOf(
                arrayOf("phone", 360, 780),
                arrayOf("tablet", 1280, 800),
            )
    }
}

@Composable
private fun Placeholder(tag: String) {
    Text(text = tag, modifier = Modifier.fillMaxSize().testTag(tag))
}

package com.iris.localai.v1141

import com.iris.localai.data.AppSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V1141Test {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun src(p: String) = File("src/main/java/com/iris/localai/$p").readText()

    @Test fun appearanceDefaults() {
        val s = AppSettings()
        assertFalse("top-bar capsules off by default", s.topBarCapsules)
        assertTrue(s.showStatusRack)
        // settings saved by 1.14.0 (no new keys) still load
        val old = json.decodeFromString(AppSettings.serializer(), "{}")
        assertFalse(old.topBarCapsules)
    }

    @Test fun dictationKeepsTheTextFieldAndHoldsUntilRelease() {
        val c = src("ui/chat/Composer.kt")
        assertTrue(c.contains("readOnly = recording"))
        assertTrue(c.contains("awaitEachGesture"))
        assertFalse("hold-to-talk must not use detectTapGestures (cancels when the finger leaves the button)",
            c.substringAfter("private fun HoldToTalkButton").substringBefore("private fun RoundAction").contains("detectTapGestures"))
    }

    @Test fun featureIconsUseIrisSet() {
        val s = src("ui/settings/SettingsScreens.kt")
        listOf("IrisIcons.Agent", "IrisIcons.Memory", "IrisIcons.Voice", "IrisIcons.Power", "IrisIcons.Think").forEach {
            assertTrue(it, s.contains(it))
        }
        assertFalse(s.contains("Icons.Outlined.BatterySaver"))
    }
}

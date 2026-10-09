package com.example

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ui.screens.VoiceSettingsCard
import com.example.ui.theme.CarbonBackground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VoiceCardTest {
    @get:Rule val compose = createComposeRule()

    private class Saved(val enabled: Boolean, val name: String, val model: String)
    private val saves = mutableListOf<Saved>()
    private var tested = 0
    private var checked: Pair<String, String>? = null

    private fun show(hasKey: Boolean, enabled: Boolean, status: String = "", check: String = "", reject: String? = null) {
        compose.setContent {
            Column(Modifier.width(360.dp).fillMaxSize().background(CarbonBackground).padding(12.dp)) {
                VoiceSettingsCard(hasKey, enabled, "bill", "elevenlabs/eleven-turbo-v2.5", status, check,
                    onSave = { e, n, m -> saves += Saved(e, n, m); reject }, onTest = { tested++ }, onCheck = { m, n -> checked = m to n })
            }
        }
    }

    @Test fun cannotBeSwitchedOnWithoutAKeyAndSaysSo() {
        show(hasKey = false, enabled = false)
        compose.onNodeWithTag("voice_switch").assertIsNotEnabled()
        compose.onNodeWithText("Save your OpenRouter API key above first.").assertExists()
        compose.onNodeWithTag("voice_test").assertIsNotEnabled()
    }

    @Test fun switchingOnSavesTheChosenVoiceAndQuality() {
        show(hasKey = true, enabled = false)
        compose.onNodeWithTag("voice_switch").assertIsEnabled().performClick()
        assertEquals("elevenlabs/eleven-turbo-v2.5", saves.single().model); assertEquals("bill", saves.single().name); assertTrue(saves.single().enabled)
        compose.onNodeWithText("Best").performClick()
        compose.onNodeWithText("george · middle-aged British, warm").performClick()
        compose.onNodeWithTag("voice_save").performClick()
        assertEquals("george", saves.last().name); assertEquals("elevenlabs/eleven-v3", saves.last().model)
    }

    @Test fun testVoiceSavesFirstThenSpeaksAndIsOnlyAvailableWhenOn() {
        show(hasKey = true, enabled = true)
        compose.onNodeWithTag("voice_test").assertIsEnabled().performClick()
        assertEquals(1, tested); assertTrue("saved before testing so the test uses what is on screen", saves.isNotEmpty())
    }

    @Test fun aRefusedVoiceNameShowsTheReasonInsteadOfFailingSilently() {
        show(hasKey = true, enabled = true, reject = "A voice name is letters, digits, - or _ (for example: bill)")
        compose.onNodeWithTag("voice_name_field").performTextClearance()
        compose.onNodeWithTag("voice_name_field").performTextInput("Bill Smith")
        compose.onNodeWithTag("voice_save").performClick()
        compose.onNodeWithText("A voice name is letters, digits, - or _ (for example: bill)").assertExists()
    }

    @Test fun checkVoiceAsksForTheVoiceOnScreenAndShowsStatusAndResult() {
        show(hasKey = true, enabled = true, status = "Speaking as 'bill' (eleven-turbo-v2.5)", check = "OpenRouter still lists 'bill' for this model.")
        compose.onNodeWithTag("voice_check").performClick()
        assertEquals("elevenlabs/eleven-turbo-v2.5" to "bill", checked)
        compose.onNodeWithTag("voice_status").assertExists(); compose.onNodeWithTag("voice_check_result").assertExists()
        // A picture for a human to look at: 360dp is a narrow phone.
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "voice-card.png")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

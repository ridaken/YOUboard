// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.keyboard.emoji

import android.view.View
import com.youboard.keyboard.ShadowInputMethodService
import com.youboard.keyboard.event.Event
import com.youboard.keyboard.keyboard.KeyboardElement
import com.youboard.keyboard.keyboard.KeyboardSwitcher
import com.youboard.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import com.youboard.keyboard.latin.LatinIME
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodService::class])
class EmojiSearchActivityTest {
    private lateinit var service: ServiceController<LatinIME>
    private var activity: ActivityController<EmojiSearchActivity>? = null
    private val switcher get() = KeyboardSwitcher.getInstance()

    @Before fun setup() {
        ShadowInputMethodService.reset()
        service = Robolectric.buildService(LatinIME::class.java).create()
        switcher.onCreateInputView(service.get(), true)
        switcher.reloadMainKeyboard()
    }

    @After fun tearDown() {
        activity?.destroy()
        service.destroy()
    }

    @Test fun `search stays alphabetic across editor reloads after the emoji key`() {
        switcher.onEvent(Event.createSoftwareKeypressEvent(KeyCode.EMOJI, 0, 0, 0, false), 0, null)
        assertSearchSurvivesReload()
    }

    @Test fun `search stays alphabetic across editor reloads after the toolbar button`() {
        switcher.setEmojiKeyboard()
        assertSearchSurvivesReload()
    }

    private fun assertSearchSurvivesReload() {
        assertEquals(View.VISIBLE, switcher.emojiPalettesView.visibility)
        activity = Robolectric.buildActivity(EmojiSearchActivity::class.java).create()
        assertEquals(KeyboardElement.ALPHABET, switcher.keyboard!!.mId.element)

        // Starting the search editor, restarting its input connection, and changing the
        // keyboard environment all save and restore the keyboard's internal mode.
        repeat(3) {
            switcher.reloadMainKeyboard()
            assertEquals(View.GONE, switcher.emojiPalettesView.visibility)
            assertEquals(View.VISIBLE, switcher.mainKeyboardView.visibility)
            assertEquals(KeyboardElement.ALPHABET, switcher.keyboard!!.mId.element)
        }
    }
}

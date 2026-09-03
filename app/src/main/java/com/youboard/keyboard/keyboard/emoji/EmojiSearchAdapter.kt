// SPDX-License-Identifier: GPL-3.0-only
package com.youboard.keyboard.keyboard.emoji

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.youboard.keyboard.keyboard.Key
import com.youboard.keyboard.keyboard.Keyboard
import com.youboard.keyboard.keyboard.internal.KeyboardParams
import com.youboard.keyboard.keyboard.internal.keyboard_parser.EMOJI_HINT_LABEL
import com.youboard.keyboard.keyboard.internal.keyboard_parser.getCode
import com.youboard.keyboard.keyboard.internal.keyboard_parser.getEmojiPopupSpec
import com.youboard.keyboard.latin.utils.prefs

/** Displays one lazily-created row of emoji keys per RecyclerView item. */
internal class EmojiSearchAdapter(
    private val context: Context,
    template: Keyboard,
    private val keyboardParams: KeyboardParams,
    private val width: Int,
    private val keyWidth: Float,
    private val keyHeight: Float,
    private val callback: EmojiViewCallback,
) : RecyclerView.Adapter<EmojiSearchAdapter.Holder>() {
    private val templateKeyboard = template
    private val columns = DynamicGridKeyboard.ofRowCount(context.prefs(), template, 1, false, width)
        .occupiedColumnCount
    private var rows: List<List<String>> = emptyList()

    fun submit(emojis: List<String>) {
        rows = emojis.chunked(columns)
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = EmojiPageKeyboardView(context, null)
        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyHeight.toInt())
        view.background = null
        view.setEmojiViewCallback(callback)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val keyboard = DynamicGridKeyboard.ofRowCount(context.prefs(), templateKeyboard, 1, false, width)
        rows[position].forEach { emoji ->
            val popupSpec = getEmojiPopupSpec(emoji)
            val params = Key.KeyParams(
                emoji, emoji.getCode(), if (popupSpec != null) EMOJI_HINT_LABEL else null,
                popupSpec, Key.LABEL_FLAGS_FONT_NORMAL, keyboardParams
            )
            params.mAbsoluteWidth = keyWidth
            params.mAbsoluteHeight = keyHeight
            keyboard.addKeyLast(params.createKey())
        }
        holder.view.setKeyboard(keyboard)
    }

    override fun onViewRecycled(holder: Holder) {
        holder.view.releaseCurrentKey(false)
        holder.view.deallocateMemory()
    }

    internal class Holder(val view: EmojiPageKeyboardView) : RecyclerView.ViewHolder(view)
}

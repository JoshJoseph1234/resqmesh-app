
package com.resqmesh.app.util

import android.text.InputFilter
import android.text.Spanned
import java.util.regex.Pattern

class EmojiAndSpecialCharacterFilter : InputFilter {

    private val emojiAndSpecialCharsPattern = Pattern.compile(
        "[\uD83C-\uDBFF\uDC00-\uDFFF\u200D\u2600-\u27BF\u2B50\uFE0F\u0020-\u002F\u003A-\u0040\u005B-\u0060\u007B-\u007E]"
    )

    override fun filter(
        source: CharSequence,
        start: Int,
        end: Int,
        dest: Spanned,
        dstart: Int,
        dend: Int
    ): CharSequence? {
        val matcher = emojiAndSpecialCharsPattern.matcher(source)
        if (matcher.find()) {
            return ""
        }
        return null
    }
}

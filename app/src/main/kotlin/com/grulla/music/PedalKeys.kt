package com.grulla.music

import android.view.KeyEvent

/** Keys sent by Bluetooth page-turn pedals (and keyboards). */
object PedalKeys {
    /** +1 next page, -1 previous page, 0 not a page-turn key. */
    fun direction(keyCode: Int, arrowKeys: Boolean): Int = when (keyCode) {
        KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> 1
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> -1
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> if (arrowKeys) 1 else 0
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> if (arrowKeys) -1 else 0
        else -> 0
    }
}

package com.ddomobile.launcher.input

import android.view.KeyEvent

/** Linux evdev codes, as the compositor forwards them to the guest. */
object Keys {
    const val ESC = 1
    const val N1 = 2
    const val MINUS = 12
    const val EQUAL = 13
    const val BACKSPACE = 14
    const val TAB = 15
    const val Q = 16
    const val W = 17
    const val E = 18
    const val R = 19
    const val T = 20
    const val I = 23
    const val ENTER = 28
    const val LEFTCTRL = 29
    const val A = 30
    const val S = 31
    const val D = 32
    const val F = 33
    const val GRAVE = 41
    const val LEFTSHIFT = 42
    const val Z = 44
    const val X = 45
    const val C = 46
    const val M = 50
    const val SLASH = 53
    const val LEFTALT = 56
    const val SPACE = 57
    const val F1 = 59

    const val BTN_LEFT = 0x110
    const val BTN_RIGHT = 0x111
    const val BTN_MIDDLE = 0x112

    fun digit(n: Int) = if (n == 0) 11 else N1 + n - 1

    private val letters = intArrayOf(30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44)

    /** Android key code to evdev, or 0 when the key has no PC equivalent. */
    fun fromAndroid(code: Int): Int = when (code) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> letters[code - KeyEvent.KEYCODE_A]
        in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> N1 + code - KeyEvent.KEYCODE_1
        KeyEvent.KEYCODE_0 -> 11
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F10 -> F1 + code - KeyEvent.KEYCODE_F1
        KeyEvent.KEYCODE_F11 -> 87
        KeyEvent.KEYCODE_F12 -> 88
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> ENTER
        KeyEvent.KEYCODE_SPACE -> SPACE
        KeyEvent.KEYCODE_TAB -> TAB
        KeyEvent.KEYCODE_DEL -> BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> 111
        KeyEvent.KEYCODE_ESCAPE -> ESC
        KeyEvent.KEYCODE_MINUS -> MINUS
        KeyEvent.KEYCODE_EQUALS -> EQUAL
        KeyEvent.KEYCODE_LEFT_BRACKET -> 26
        KeyEvent.KEYCODE_RIGHT_BRACKET -> 27
        KeyEvent.KEYCODE_BACKSLASH -> 43
        KeyEvent.KEYCODE_SEMICOLON -> 39
        KeyEvent.KEYCODE_APOSTROPHE -> 40
        KeyEvent.KEYCODE_GRAVE -> GRAVE
        KeyEvent.KEYCODE_COMMA -> 51
        KeyEvent.KEYCODE_PERIOD -> 52
        KeyEvent.KEYCODE_SLASH -> SLASH
        KeyEvent.KEYCODE_SHIFT_LEFT -> LEFTSHIFT
        KeyEvent.KEYCODE_SHIFT_RIGHT -> 54
        KeyEvent.KEYCODE_CTRL_LEFT -> LEFTCTRL
        KeyEvent.KEYCODE_CTRL_RIGHT -> 97
        KeyEvent.KEYCODE_ALT_LEFT -> LEFTALT
        KeyEvent.KEYCODE_ALT_RIGHT -> 100
        KeyEvent.KEYCODE_CAPS_LOCK -> 58
        KeyEvent.KEYCODE_DPAD_UP -> 103
        KeyEvent.KEYCODE_DPAD_DOWN -> 108
        KeyEvent.KEYCODE_DPAD_LEFT -> 105
        KeyEvent.KEYCODE_DPAD_RIGHT -> 106
        KeyEvent.KEYCODE_PAGE_UP -> 104
        KeyEvent.KEYCODE_PAGE_DOWN -> 109
        KeyEvent.KEYCODE_MOVE_HOME -> 102
        KeyEvent.KEYCODE_MOVE_END -> 107
        KeyEvent.KEYCODE_INSERT -> 110
        else -> 0
    }

    /** Evdev code and whether shift is needed to type [c] on a US layout, for text from the soft keyboard. */
    fun forChar(c: Char): Pair<Int, Boolean>? = when (c) {
        in 'a'..'z' -> letters[c - 'a'] to false
        in 'A'..'Z' -> letters[c - 'A'] to true
        in '1'..'9' -> (N1 + (c - '1')) to false
        '0' -> 11 to false
        ' ' -> SPACE to false
        '\n' -> ENTER to false
        '-' -> MINUS to false
        '_' -> MINUS to true
        '=' -> EQUAL to false
        '+' -> EQUAL to true
        '[' -> 26 to false
        '{' -> 26 to true
        ']' -> 27 to false
        '}' -> 27 to true
        '\\' -> 43 to false
        '|' -> 43 to true
        ';' -> 39 to false
        ':' -> 39 to true
        '\'' -> 40 to false
        '"' -> 40 to true
        '`' -> GRAVE to false
        '~' -> GRAVE to true
        ',' -> 51 to false
        '<' -> 51 to true
        '.' -> 52 to false
        '>' -> 52 to true
        '/' -> SLASH to false
        '?' -> SLASH to true
        '!' -> N1 to true
        '@' -> (N1 + 1) to true
        '#' -> (N1 + 2) to true
        '$' -> (N1 + 3) to true
        '%' -> (N1 + 4) to true
        '^' -> (N1 + 5) to true
        '&' -> (N1 + 6) to true
        '*' -> (N1 + 7) to true
        '(' -> (N1 + 8) to true
        ')' -> 11 to true
        else -> null
    }
}

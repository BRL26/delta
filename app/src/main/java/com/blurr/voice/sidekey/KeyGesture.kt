package com.blurr.voice.sidekey

/**
 * The gestures the classifier can recognise from a single physical key.
 *
 * This package mirrors the proven accessibility-service approach from the
 * MIT-licensed project KoukeNeko/EssentialKeyTools (adapted with permission
 * for Delta): the Nothing Essential Key arrives as `keyCode=0` /
 * Linux `scanCode=250`, which public key-layout files leave unmapped, so an
 * accessibility service with key-event filtering can observe it and classify
 * single / double / triple / long presses.
 */
enum class KeyGesture {
    SINGLE_PRESS,
    DOUBLE_PRESS,
    TRIPLE_PRESS,
    LONG_PRESS
}
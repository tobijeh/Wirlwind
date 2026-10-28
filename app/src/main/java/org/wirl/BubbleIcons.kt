package org.wirl

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * The glyphs the floating bubble can show, in picker order. Each is a white vector on the accent circle.
 * The first one is the default, the Wirl swirl. The launcher, notification, and Quick Settings tile always use the swirl.
 */
object BubbleIcons {
    data class Icon(val id: String, @DrawableRes val drawable: Int, @StringRes val label: Int)

    val ALL: List<Icon> = listOf(
        Icon("swirl", R.drawable.bubble_icon_swirl, R.string.icon_swirl),
        Icon("mic", R.drawable.bubble_icon_mic, R.string.icon_mic),
        Icon("book", R.drawable.bubble_icon_book, R.string.icon_book),
        Icon("text", R.drawable.bubble_icon_text, R.string.icon_text),
        Icon("plane", R.drawable.bubble_icon_plane, R.string.icon_plane),
        Icon("keyboard", R.drawable.bubble_icon_keyboard, R.string.icon_keyboard),
        Icon("cloud", R.drawable.bubble_icon_cloud, R.string.icon_cloud),
        Icon("sprout", R.drawable.bubble_icon_sprout, R.string.icon_sprout),
        Icon("paw", R.drawable.bubble_icon_paw, R.string.icon_paw),
        Icon("palette", R.drawable.bubble_icon_palette, R.string.icon_palette),
        Icon("laptop", R.drawable.bubble_icon_laptop, R.string.icon_laptop),
        Icon("heart", R.drawable.bubble_icon_heart, R.string.icon_heart),
        Icon("bolt", R.drawable.bubble_icon_bolt, R.string.icon_bolt),
        Icon("sparkles", R.drawable.bubble_icon_sparkles, R.string.icon_sparkles),
        Icon("wave", R.drawable.bubble_icon_wave, R.string.icon_wave),
    )

    const val DEFAULT = "swirl"

    /** The icon with this id, or the swirl when the id is missing or unknown. */
    fun find(id: String?): Icon = ALL.firstOrNull { it.id == id } ?: ALL.first()
}

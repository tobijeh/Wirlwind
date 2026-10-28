package org.wirl

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * The glyphs the floating bubble can show, in picker order. Each is a white vector on the accent circle.
 * The first one is the default, the Wirl swirl. Removed ids fall back to the swirl. The launcher, notification, and Quick Settings tile always use the swirl.
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
        Icon("chat", R.drawable.bubble_icon_chat, R.string.icon_chat),
        Icon("sparkles", R.drawable.bubble_icon_sparkles, R.string.icon_sparkles),
        Icon("sprout", R.drawable.bubble_icon_sprout, R.string.icon_sprout),
        Icon("paw", R.drawable.bubble_icon_paw, R.string.icon_paw),
        Icon("palette", R.drawable.bubble_icon_palette, R.string.icon_palette),
        Icon("wave", R.drawable.bubble_icon_wave, R.string.icon_wave),
        Icon("mountains", R.drawable.bubble_icon_mountains, R.string.icon_mountains),
        Icon("tree", R.drawable.bubble_icon_tree, R.string.icon_tree),
        Icon("butterfly", R.drawable.bubble_icon_butterfly, R.string.icon_butterfly),
        Icon("bee", R.drawable.bubble_icon_bee, R.string.icon_bee),
        Icon("hummingbird", R.drawable.bubble_icon_hummingbird, R.string.icon_hummingbird),
        Icon("dolphin", R.drawable.bubble_icon_dolphin, R.string.icon_dolphin),
        Icon("seahorse", R.drawable.bubble_icon_seahorse, R.string.icon_seahorse),
        Icon("jellyfish", R.drawable.bubble_icon_jellyfish, R.string.icon_jellyfish),
        Icon("whale", R.drawable.bubble_icon_whale, R.string.icon_whale),
        Icon("turtle", R.drawable.bubble_icon_turtle, R.string.icon_turtle),
        Icon("bird", R.drawable.bubble_icon_bird, R.string.icon_bird),
        Icon("rocket", R.drawable.bubble_icon_rocket, R.string.icon_rocket),
        Icon("pizza", R.drawable.bubble_icon_pizza, R.string.icon_pizza),
    )

    const val DEFAULT = "swirl"

    /** The icon with this id, or the swirl when the id is missing or unknown. */
    fun find(id: String?): Icon = ALL.firstOrNull { it.id == id } ?: ALL.first()
}

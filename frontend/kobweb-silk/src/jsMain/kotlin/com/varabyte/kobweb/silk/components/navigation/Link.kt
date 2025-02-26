package com.varabyte.kobweb.silk.components.navigation

import androidx.compose.runtime.*
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.asAttributeBuilder
import com.varabyte.kobweb.silk.components.style.ComponentVariant
import com.varabyte.kobweb.silk.components.style.toModifier
import org.jetbrains.compose.web.dom.Text
import com.varabyte.kobweb.navigation.Link as KobwebLink

/**
 * Linkable text which, when clicked, navigates to the target [path].
 *
 * This composable is SilkTheme-aware, and if colors are not specified, will automatically use the current theme plus
 * color mode.
 */
@Composable
fun Link(
    path: String,
    text: String? = null,
    modifier: Modifier = Modifier,
    variant: ComponentVariant? = null
) {
    KobwebLink(
        href = path,
        attrs = LinkStyle.toModifier(variant).then(modifier).asAttributeBuilder()
    ) {
        Text(text ?: path)
    }
}
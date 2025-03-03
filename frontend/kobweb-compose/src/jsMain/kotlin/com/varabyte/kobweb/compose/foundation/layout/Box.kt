package com.varabyte.kobweb.compose.foundation.layout

import androidx.compose.runtime.*
import com.varabyte.kobweb.compose.style.toClassName
import com.varabyte.kobweb.compose.ui.Alignment
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.asAttributeBuilder
import com.varabyte.kobweb.compose.ui.attrModifier
import org.jetbrains.compose.web.dom.Div

class BoxScope {
    fun Modifier.align(alignment: Alignment) = attrModifier {
        classes("${alignment.toClassName()}-self")
    }
}

@Composable
fun Box(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit = {}
) {
    Div(attrs = modifier.asAttributeBuilder {
        classes("kobweb-box", contentAlignment.toClassName())
    }) {
        BoxScope().content()
    }
}
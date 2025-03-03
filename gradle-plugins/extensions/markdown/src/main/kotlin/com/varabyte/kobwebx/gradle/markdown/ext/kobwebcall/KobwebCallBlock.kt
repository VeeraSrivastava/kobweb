package com.varabyte.kobwebx.gradle.markdown.ext.kobwebcall

import org.commonmark.node.CustomBlock

/**
 * The block object that owns the nodes which will be used to extract the text for the Kobweb call from.
 *
 * For example, "{{ test }}" will create a block with a single node as its child containing the "test" information.
 */
class KobwebCallBlock : CustomBlock()
package io.technoirlab.device.run.core

import org.w3c.dom.Element
import org.w3c.dom.NodeList

/**
 * Returns the elements among the nodes, in their order.
 */
internal fun NodeList.elements(): List<Element> = List(length) { item(it) }.filterIsInstance<Element>()

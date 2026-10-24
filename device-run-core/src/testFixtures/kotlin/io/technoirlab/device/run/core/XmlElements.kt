package io.technoirlab.device.run.core

import org.w3c.dom.Element
import org.w3c.dom.NodeList

/**
 * Returns the elements among the nodes, in their order.
 */
internal fun NodeList.elements(): List<Element> = List(length) { item(it) }.filterIsInstance<Element>()

/**
 * Returns the child elements named [tagName], in their order.
 */
internal fun Element.childElements(tagName: String): List<Element> = childNodes.elements().filter { it.tagName == tagName }

/**
 * Returns the only child element named [tagName].
 */
internal fun Element.child(tagName: String): Element = childElements(tagName).single()

/**
 * Returns the names of the child elements, in their order.
 */
internal fun Element.childTags(): List<String> = childNodes.elements().map { it.tagName }

/**
 * Returns the values of the attributes [names] in their order, with an empty value for a missing attribute.
 */
internal fun Element.attributes(vararg names: String): List<String> = names.map { getAttribute(it) }

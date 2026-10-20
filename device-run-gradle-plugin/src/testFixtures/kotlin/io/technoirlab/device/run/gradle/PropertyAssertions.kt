package io.technoirlab.device.run.gradle

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.provider.Property

/**
 * Asserts that [property] rejects [value] because changes to its value are disallowed.
 */
internal fun <T : Any> assertFinal(property: Property<T>, value: T) {
    assertThatThrownBy { property.set(value) }
        .isInstanceOf(IllegalStateException::class.java)
        .hasMessage("The value for $property cannot be changed any further.")
}

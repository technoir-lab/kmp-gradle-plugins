# Template overrides

The baseline is OpenAPI Generator **7.26.0**, as pinned in `gradle/libs.versions.toml`.
Resolve each template from `kotlin-client/libraries/multiplatform/` first, then `kotlin-client/` in the generator JAR.
The matching [upstream source tree](https://github.com/OpenAPITools/openapi-generator/tree/v7.26.0/modules/openapi-generator/src/main/resources/kotlin-client)
provides the versioned reference.
`licenseInfo.mustache` supplies the generated-source notice. `api.mustache` supplies the Ktor API implementation.
The remaining templates were added as unmodified upstream copies; their history records each change.

Keep an override only when it fixes generated Kotlin, implements the documented serialization contract, or emits
the required source style. Preserve upstream template structure and unrelated library branches.

The functional-test fixture checks in the raw output of `openApiGenerate`, and a functional test compares it with a
fresh generation. After an intentional change, run the functional tests with `UPDATE_CHECKED_IN_CLIENT=true` to replace
the checked-in client, then review its diff.

## Overrides

| Templates | Change | Reason |
|---|---|---|
| `data_class`, `enum_class` | Import kotlinx.serialization declarations explicitly. | KtLint cannot correct wildcard imports, so formatting failed generation. |
| `data_class_*_var`, `interface_*_var`, `data_class` | Put property and enum entry annotations on separate lines, and omit blank lines between class annotations. | Kotlin conventions place annotations on their own lines, directly above the declaration; generated sources follow them even without KtLint. Upstream emits a blank line after `@Serializable` and another before the discriminator annotations of sealed classes. |
| `data_class` | Emit the multiplatform superclass constructor call only when the map or array branch has not emitted one. | Upstream emits two calls, such as `Pet()()`, for subclasses whose schemas set `additionalProperties`, which fails to parse. |

## Generator adjustments

`KotlinClientGenerator` corrects model data that upstream templates render, so the templates can stay unchanged.

| Adjustment | Reason |
|---|---|
| Restore `allVars` and `hasEnums` from `vars` when composed schema processing leaves `allVars` empty. | Upstream resets both when `oneOf` or `anyOf` alternatives only add constraints, emitting empty data class constructors and omitting nested enums. |
| Omit the `HashMap` superclass of models whose schemas set `additionalProperties` and the `ArrayList` superclass of array models. | Both classes are final outside the JVM, so these models failed to compile in common source sets. kotlinx.serialization ignores superclass state, so the inherited entries were never serialized. |

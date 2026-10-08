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

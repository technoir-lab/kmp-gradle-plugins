OpenAPI Kotlin Generator
========================

Gradle plugin that applies OpenAPI Kotlin generation conventions.

## Usage

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm") // Or org.jetbrains.kotlin.multiplatform.
    id("org.jlleitschuh.gradle.ktlint") // Optional; apply to format the generated code automatically.
    id("io.technoirlab.openapi-kotlin-generator")
}

openApiKotlinGenerator {
    specUrl = uri("https://spec/url.yaml") // Local files, such as uri("petstore.yaml"), also work.
    packageName = "com.example.client" // Optional when a Technoir Lab convention supplies the module's package name.
    publicApi = false // Optional; defaults to true.
}
```

Run `./gradlew :module:openApiGenerate` whenever you want to generate or update the client. It writes Kotlin API classes
and models to `src/main/kotlin`, or to `src/commonMain/kotlin` when the Kotlin Multiplatform plugin is applied, in the
`.api` and `.model` subpackages of the configured package. Generation runs only when explicitly requested and finishes
by formatting that source set when KtLint is applied. You can validate the specification separately with
`./gradlew :module:openApiValidate`.

Each generation first runs `openApiClean`, which deletes previously generated Kotlin files recorded in
`.openapi-generator/FILES` under the source directory. This removes stale APIs and models after a specification change
while preserving handwritten files absent from the manifest. Keep the manifest with the generated sources so cleanup
works across checkouts. If it is missing, cleanup skips deletion and generation creates a new manifest. The generator
also records its version in `.openapi-generator/VERSION`. Run `./gradlew :module:openApiClean` to remove generated
sources without regenerating them.

Generated API classes accept a Ktor `HttpClient` and expose suspending methods that decode responses using
kotlinx.serialization. The caller configures the client's base URL, authentication, and JSON content negotiation and
manages its lifetime. Declare dependencies on `io.ktor:ktor-client-core` and
`org.jetbrains.kotlinx:kotlinx-serialization-core`, supplying a Ktor version directly or through its BOM. In Kotlin
Multiplatform projects, declare them for `commonMain`.

The plugin configures OpenAPI Generator with:

- Generator: a `KotlinClientCodegen` subclass that corrects model data before rendering.
- Library: `multiplatform`, using Ktor and custom templates.
- Serialization library: `kotlinx_serialization`.
- Date library: `kotlinx-datetime`.
- Enum property naming: `UPPERCASE`.
- Union wrappers: `generateOneOfAnyOfWrappers = true`.
- Normalization: `REPLACE_ONE_OF_BY_DISCRIMINATOR_MAPPING = true`.
- Type mappings: base64-encoded strings use `String`; UUIDs use `kotlin.uuid.Uuid`; binary payloads use `ByteArray`;
  untyped objects and `AnyType` use `kotlinx.serialization.json.JsonElement`.
- Source directory: `src/commonMain/kotlin` for Kotlin Multiplatform projects and `src/main/kotlin` otherwise, with
  `.api` and `.model` subpackages.
- Generated content: all APIs and models, plus the `FILES` and `VERSION` metadata files. API and model tests,
  documentation, supporting source files, build scripts, and the Gradle wrapper are disabled or excluded.
- Visibility: public by default, or internal when `publicApi` is `false`.

Object schemas whose `oneOf` or `anyOf` alternatives only add constraints, such as `required`, generate a data class
with the properties the schema declares. The generated class does not enforce those constraints.

Named `oneOf` schemas with a discriminator support model alternatives referenced with `$ref` and generate sealed class
hierarchies. Use the generated subclasses directly; the discriminator property in JSON identifies the subtype, using
the schema's explicit or inferred mapping. Subtype selection follows that property even when alternatives overlap.
Inline unions with a discriminator and `anyOf` schemas with a discriminator are unsupported.

## Templates

Run `./extract-templates.sh` to extract upstream templates using the `openapi-generator` CLI and replace matching files
in `src/main/resources/openapi-generator-templates`.
Use the CLI version pinned in the version catalog. See [template overrides](TEMPLATES.md) for the retained changes and
their reasons.

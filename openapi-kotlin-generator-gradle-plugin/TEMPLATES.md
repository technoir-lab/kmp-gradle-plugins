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
| `oneof_class`, `anyof_class`, `union_class` | Render both wrapper kinds with one serializer that resolves each alternative's serializer from the `Json` instance's module and enforces the matching rules below. | Upstream wrappers fail to compile for collection and date-time alternatives, cannot encode `Long` or map alternatives, cannot decode `null` for nullable unions, and decode the first alternative that succeeds, dropping fields of overlapping alternatives. |
| `api` | Send the text of the value that union parameters hold in `actualInstance`, and of each item's value in lists of union wrappers. Send a collection that a union holds like a list parameter in the same location. | The template converted parameters that are not strings with `toString()`, so union parameters sent the wrapper's data class text, such as `PetReference(actualInstance=Mochi)`, in paths, queries, headers, cookies, and forms. |
| `data_class_*_var`, `interface_*_var`, `data_class`, `enum_class` | Refer to Kotlin collections, `Array`, `String`, and `Any` by unqualified names. | Kotlin imports them by default, so the qualifiers are redundant. |
| `api`, `data_class` | Skip `toString()` for path, query, and form parameters marked `x-string-value`, and for enum values that are strings, including enums of list items. Send URL-encoded form lists as repeated fields. | The Kotlin compiler warns about redundant conversion calls, which fails builds that treat warnings as errors. URL-encoded forms sent lists as their `toString()` text, such as `[a, b]`. |
| `data_class_*_var`, `interface_*_var`, `data_class` | Put property and enum entry annotations on separate lines, and omit blank lines between class annotations. | Kotlin conventions place annotations on their own lines, directly above the declaration; generated sources follow them even without KtLint. Upstream emits a blank line after `@Serializable` and another before the discriminator annotations of sealed classes. |
| `data_class_*_var`, `interface_*_var` | Document properties with multiline KDoc instead of block comments. | Block comments are not documentation, so IDEs and Dokka ignored property descriptions, including those of abstract properties that class KDoc does not cover. |
| `api` | Generate API classes that receive Ktor's `HttpClient` as their only client dependency. | Decouple API bindings from the generated REST client infrastructure so consumers control HTTP client configuration and lifecycle. |
| `data_class`, `enum_class`, `api` | Omit the description paragraph from class, enum, and parameter KDoc when there is no description. | Upstream emits a blank description line and trailing spaces. |
| `data_class` | Open the constructor directly after the class name, end each parameter with a comma, and omit blank lines at the constructor's boundaries. | Kotlin conventions use trailing commas in multiline declarations and no space before the parameter list. |
| `enum_class`, `data_class`, `api` | Separate enum entries from each other and from the declarations that follow with one blank line, and precede each nested enum class and the enum serializer object with one. Omit blank lines at the start of enum bodies, and indent `decode` by four spaces. | Each template laid out enums differently: top-level enums opened with a blank line, nested enums had no blank lines between entries, `toString()`, or consecutive enum classes, and API enums contained whitespace-only lines. |
| `data_class` | Emit the multiplatform superclass constructor call only when the map or array branch has not emitted one. | Upstream emits two calls, such as `Pet()()`, for subclasses whose schemas set `additionalProperties`, which fails to parse. |

## Union matching

The [OpenAPI composition rules](https://spec.openapis.org/oas/v3.0.3.html#schema-object) motivate the matching counts;
the rules below cover only the generator's supported subset. Removing each rule fails the listed fixture tests.

| Rule | Evidence |
|---|---|
| Resolve serializers with `json.serializersModule.serializer<T>()`. | Collection and instant alternatives compile and round-trip. |
| Check collection element and map key and value types before encoding. | 4 tests. JVM bridge methods would otherwise narrow `Long` map values to `Int`. |
| Count matches on read and write: exactly one for `oneOf`, at least one for `anyOf`. | 2 tests for the write side: empty lists and overlapping objects. |
| Match with a strict copy of the caller's `Json` that ignores unknown keys and encodes defaults and nulls. | 2 tests: overlapping objects must both match, and optional fields holding default values must survive re-encoding. |
| Compare the JSON kinds of decoded and re-encoded values recursively. | 4 tests: quoted numbers and numbers read as strings. |
| Select the first match that retains the most input fields at any depth. | 2 tests: overlapping `anyOf` alternatives, with and without unknown fields. |
| Fail if the selected match drops an input field, unless the caller's `Json` ignores unknown keys. | 10 tests, 5 with each setting. Regular models drop unknown keys under the same condition. Read the setting before switching to the strict copy, which always ignores unknown keys. |
| Skip primitive serializers for objects and arrays. | 10 tests. Primitive serializers throw `IndexOutOfBoundsException` for these shapes. |
| Match alternatives marked `x-closed-object` only against objects whose properties they declare. | 4 tests. Alternatives without declared properties map to `JsonElement`, which otherwise matches every value. |
| Treat `ClassCastException` during encoding as a mismatch. | 2 tests with list elements of the wrong type. |

## Generator adjustments

`KotlinClientGenerator` corrects model data that upstream templates render, so the templates can stay unchanged.

| Adjustment | Reason |
|---|---|
| Restore `allVars` and `hasEnums` from `vars` when composed schema processing leaves `allVars` empty. | Upstream resets both when `oneOf` or `anyOf` alternatives only add constraints, emitting empty data class constructors and omitting nested enums. |
| Omit the `HashMap` superclass of models whose schemas set `additionalProperties` and the `ArrayList` superclass of array models. | Both classes are final outside the JVM, so these models failed to compile in common source sets. kotlinx.serialization ignores superclass state, so the inherited entries were never serialized. |
| Reserve only Kotlin's hard keywords. | Upstream also reserves soft and modifier keywords, such as `field` and `operator`, identifiers such as `it`, and its unused `ApiResponse` class name. Kotlin accepts these names wherever the templates emit them, so upstream added unnecessary backticks and renamed operations and models, such as `import` to `callImport`. |
| Remove the backticks from enum class names derived from escaped property and parameter names, unless the name is not a plain identifier. | Upstream derives Pascal case names after escaping Kotlin keywords, so a property named `for` produced the enum class ``` `For` ```, which KtLint reports and cannot correct, and a parameter named `in` produced `` &#x60;In&#x60;ListPets ``, which fails to compile. |
| Prefix enum entry names that start with an underscore with `VALUE`. | Upstream names numeric entries `_1`, which breaks Kotlin naming conventions and fails the KtLint format task because KtLint cannot correct it. |
| Shorten type names that Kotlin imports by default or that match an import mapping, and recognize the shortened built-in types as language primitives. | Upstream qualifies built-in types and expands import mappings in declarations. |
| Pass qualified numeric and Boolean type names to upstream enum value rendering. | Upstream recognizes these types only by qualified name and otherwise quotes numeric enum values, which fails to compile. |
| Import the types of scalar union alternatives. | Upstream imports only collection alternatives, so shortened scalar alternatives such as `Instant` were unresolved. |
| Mark alternatives whose schemas set `additionalProperties: false` with `x-closed-object`. | `union_class` matches them only against objects without other properties. Otherwise an empty-object alternative, such as that of GitHub's commit authors, mapped to `JsonElement` and matched every value, so a `oneOf` with it never had exactly one match for objects. |
| Remove `JsonElement` from the imports of union models. | `union_class` imports `JsonElement` for its serializer, so unions with alternatives such as `Map<String, JsonElement>` imported it twice, which fails to compile. |
| Rename schemas whose model names collide, keeping the names of schemas declared in the original document. | Inline schema resolution names extracted schemas after their titles and only avoids exact key matches, so an inline `Adoption Record` and an `adoption-record` component both generated `AdoptionRecord.kt`, and one silently replaced the other. |
| Render default values of URI schemas as string literals. | Upstream calls `create` on the `URI` import mapping, which the multiplatform library maps to `kotlin.String`, so URI properties with defaults rendered `kotlin.String.create("")` and failed to compile. |
| Drop the `L` suffix from default values of `Long` enums. | Defaults select the enum entry with the same value. Upstream suffixes `Long` defaults but not entry values, so a parameter default referenced a missing `VALUE_20_L` entry and a property default had the wrong type. |
| Treat integers without a format as `int64`. | Upstream generates `Int`, which cannot hold values of unbounded integers such as identifiers that omit `int64`. |
| Mark parameters whose value, or every list item, is a non-null `String` with `x-string-value`. | Lets `api.mustache` skip redundant conversions without repeating the type conditions in each parameter location. |
| Mark parameters whose type is a union wrapper with `x-union`, and lists of union wrappers with `x-union-items`. Give union query parameters the collection format of an array parameter with the same `style` and `explode`, and flag API classes that have union parameters with `x-has-union-params`. | Lets `api.mustache` unwrap `actualInstance`, send a collection that a union holds like a list parameter, and declare its conversion helper only where it is used. Upstream derives collection formats only for array schemas. |
| Take the nullability of request bodies that reference a discriminator base from the schema's `nullable` keyword. | Normalization removes the base's `oneOf`, and upstream treats the remaining untyped schema as any type, so a required body became nullable, such as `addPet(pet: Pet?)`. |

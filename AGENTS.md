# Repository Guidelines

## Project Structure & Module Organization

- `cmake-import-gradle-plugin/` imports CMake projects; `vfs-overlay-gradle-plugin/` generates Clang VFS overlays.
- `kotlin-native-utils/` supplies shared JVM utilities for Kotlin/Native.
- Modules use `src/main/kotlin` and `src/test/kotlin`. Plugin APIs belong in `src/api/kotlin`; integration tests and fixtures
  use `src/functionalTest/kotlin` and `src/functionalTest/resources`. ABI snapshots live in `api/`.
- `smoke-test/` is a separate composite build exercising SDL and volk, with submodules under `third_party/`.
- Shared dependency versions live in `gradle/libs.versions.toml`; convention plugins are configured in `settings.gradle.kts`.

## Build, Test, and Development Commands

Run commands from the repository root. Use the Gradle wrapper; the daemon and CI use JDK 25. Native tests require CMake 3.29+ and applicable
platform SDKs; Windows uses Ninja. See the CMake module README for prerequisites.

- `make check`: run standard checks.
- `make test`: run unit tests.
- `make functional-test`: run Gradle TestKit tests.
- `make format`: apply KtLint formatting and sort dependencies.
- `make docs`: generate Dokka API documentation in `build/dokka/html`.
- `make abi`: regenerate Kotlin ABI snapshots after intentional public API changes; review the diff.
- `make publish-local`: publish artifacts to Maven Local for consumer testing.

Pass additional options with `make check GRADLE_ARGS="--info"`.

## Coding Style & Naming Conventions

Follow `.editorconfig`: four spaces, UTF-8, LF, final newlines, and a 140-character limit; YAML uses two spaces. Kotlin follows
KtLint's IntelliJ style with trailing commas and explicit imports. Use PascalCase class/file names and camelCase
functions/properties. Preserve lazy Gradle Provider wiring, configuration-cache support, and isolated-project compatibility.

## Testing Guidelines

Use JUnit Jupiter and AssertJ for JVM tests, and the existing `GradleRunnerExtension` for TestKit fixtures. Name classes
`*Test` or `*FunctionalTest`, with descriptive backticked test methods. Add regression coverage for changed behavior; no
repository-specific coverage percentage is configured.

For focused execution, use `./gradlew :vfs-overlay-gradle-plugin:functionalTest --tests '*VfsOverlayPluginFunctionalTest'`.

## Commits and Pull Requests

- Use descriptive branch names without AI harness prefixes (such as `codex/`, `claude/`, `cursor/`, or `junie/`).
- Keep commits focused and use short, imperative commit subjects.
- Do not add a `Co-Authored-By` trailer.
- PR descriptions should explain the problem, the changes made, and the resulting behavior. Include compatibility impacts, remaining limitations, and links to related issues when relevant. Do not include checks performed, validation commands, or validation results.
- Ensure Linux, macOS, and Windows CI passes.

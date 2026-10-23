package io.technoirlab.device.run.gradle.api

/**
 * Which tests a retry attempt runs.
 */
enum class RetryScope {
    /**
     * Every test of the failed shard.
     *
     * The shard passes only when one attempt passes all of its tests, so a test task can fail although every test passed in some
     * attempt.
     */
    SHARD,

    /**
     * Only the failed tests.
     *
     * At least one test class of the test APK must declare a runner that Device Run supports with `@RunWith`, such as
     * `AndroidJUnit4` or `Parameterized`. Otherwise Device Run silently reruns every test of the shard.
     *
     * When a test crashes the instrumentation process, the retry runs only the crashed test, so the tests after it never run, and
     * the test task fails. Android Test Orchestrator runs each test in its own process, so a crash fails only that test.
     */
    FAILED_TEST_CASES,
}

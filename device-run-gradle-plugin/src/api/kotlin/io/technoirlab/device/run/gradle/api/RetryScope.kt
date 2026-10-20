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
     */
    FAILED_TEST_CASES,
}

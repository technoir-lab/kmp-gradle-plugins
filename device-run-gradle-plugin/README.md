Device Run plugin
=================

Gradle plugin that runs the instrumentation tests of Android projects on [Device Run][device-run], part of Google's Developer
Device Platform. Device Run devices are Gradle Managed Devices: you declare them in the build script, and AGP creates test tasks that run
the tests on a device of the Device Run catalog instead of a local emulator.

The plugin supports projects that apply the Android application, library, or test plugin of AGP 9.4.1 or newer.

## Usage

Before the first test run, prepare Google Cloud:

1. A [Google Cloud project](#google-cloud-project) with the Device Run API enabled.
2. A [Cloud Storage bucket](#storage-bucket) for test inputs and outputs.
3. [Credentials](#authentication) that the build uses to call Device Run.

Then add the plugin repositories to `settings.gradle(.kts)`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

Apply the plugin in one of two ways:

- The [project plugin](#project-plugin), `io.technoirlab.device-run`, which you apply to each Android project whose tests run on
  Device Run.
- The [settings plugin](#settings-plugin), `io.technoirlab.device-run.settings`, which applies the project plugin to every Android
  project of the build.

### Project plugin

Declare the plugin in `settings.gradle(.kts)`, together with the Android plugins that your projects use:

```kotlin
plugins {
    id("com.android.application") version "<agp-version>" apply false
    id("com.android.library") version "<agp-version>" apply false
    id("io.technoirlab.device-run") version "<plugin-version>" apply false
}
```

Then apply it in the build script of each Android project whose tests run on Device Run:

```kotlin
plugins {
    id("com.android.application")
    id("io.technoirlab.device-run")
}
```

The plugin uses AGP from the settings classpath, so declare the Android plugins in the settings as above or apply
`com.android.settings`. The declaration also makes all projects use the same copy of the plugin. When only `pluginManagement` or the
build scripts of the projects set its version, each project resolves the plugin itself and can load a separate copy, and the setup
and test tasks then fail because a `buildService` property doesn't have a configured value.

### Settings plugin

Apply the plugin in `settings.gradle(.kts)`, together with the Android plugins that your projects use:

```kotlin
plugins {
    id("com.android.application") version "<agp-version>" apply false
    id("com.android.library") version "<agp-version>" apply false
    id("io.technoirlab.device-run.settings") version "<plugin-version>"
}
```

The plugin uses AGP from the settings classpath, so declare the Android plugins in the settings as above or apply
`com.android.settings`.

### Running tests

The plugin adds the `deviceRun` extension to each Android project that it is applied to. Declare a device and the bucket there:

```kotlin
deviceRun {
    devices {
        register("smallPhone") {
            deviceId = "smallphone-arm-35"
        }
    }

    storage {
        bucket = "my-device-run-bucket"
    }
}
```

Run the tests with the tasks that AGP creates for managed devices:

```shell
./gradlew :app:smallPhoneDebugAndroidTest
```

`<device><Variant>AndroidTest` runs the tests of one variant on one device. `allDevices<Variant>AndroidTest` and `allDevicesCheck` run
them on every managed device of the project, including local ones. Each test task starts one Device Run session, waits for it to
finish, and writes the [test results](#test-results) where AGP and Android Studio expect them. Run Gradle with `--info` to follow the
progress of the session.

To share the configuration between projects, configure the extension from the settings:

```kotlin
import io.technoirlab.device.run.gradle.api.DeviceRunExtension

gradle.lifecycle.beforeProject {
    pluginManager.withPlugin("io.technoirlab.device-run") {
        extensions.configure<DeviceRunExtension> {
            devices {
                register("smallPhone") {
                    deviceId = "smallphone-arm-35"
                }
            }

            storage {
                bucket = "my-device-run-bucket"
            }
        }
    }
}
```

## Google Cloud project

Device Run sessions run in, and are billed to, the project of the credentials: their quota project, or their project ID when they
have no quota project. Set the `GOOGLE_CLOUD_QUOTA_PROJECT` environment variable to select another project. Billing must be enabled
for the project.

Enable the Device Run API in the project:

```shell
gcloud services enable devicerun.googleapis.com --project=$PROJECT_ID
```

The plugin calls only the Device Run and Cloud Storage APIs.

Enabling the API creates the Device Run service agent of the project, `service-PROJECT_NUMBER@gcp-sa-devicerun.iam.gserviceaccount.com`,
and grants it `roles/devicerun.serviceAgent`. Device Run reads test inputs and writes test outputs with this service agent, not with
the credentials of the build. Find the project number with:

```shell
gcloud projects describe $PROJECT_ID --format="value(projectNumber)"
```

The principal that runs the build, a user or a service account, needs these roles in the project:

- `roles/devicerun.admin`, or a custom role with the permissions `devicerun.devices.get`, `devicerun.operations.get`,
  `devicerun.sessions.create`, `devicerun.sessions.delete`, `devicerun.sessions.get`, and `devicerun.softwareVersions.list`.
- `roles/serviceusage.serviceUsageConsumer`, because the plugin bills its Device Run and Cloud Storage requests to the project.

```shell
gcloud projects add-iam-policy-binding $PROJECT_ID --member=$PRINCIPAL --role=roles/devicerun.admin
gcloud projects add-iam-policy-binding $PROJECT_ID --member=$PRINCIPAL --role=roles/serviceusage.serviceUsageConsumer
```

Set `PRINCIPAL` to `user:EMAIL` for a user or `serviceAccount:EMAIL` for a service account. The principal also needs access to the
[bucket](#storage-bucket).

## Storage bucket

Device Run reads test inputs from and writes test outputs to a Cloud Storage bucket that you create and manage. The plugin never
creates the bucket, changes its permissions, or deletes its objects.

The bucket has these requirements:

- It exists before the first test run, and `storage.bucket` names it without the `gs://` scheme.
- The principal that runs the build has `roles/storage.objectUser` on the bucket, unless a project role already grants it access.
  The build uploads the APKs and pushed files, checks existing objects, and lists and downloads session outputs.
- The Device Run service agent can read and write the objects of the bucket. Its `roles/devicerun.serviceAgent` covers buckets of the
  Device Run project, which Google recommends for test outputs. For a bucket in another project, grant `roles/storage.objectUser` on
  the bucket to the service agent of the Device Run project.
- Access is granted through IAM. Enable uniform bucket-level access, so that object ACLs cannot block the service agent.

Create the bucket in the Device Run project and grant the principal of the build access to it:

```shell
gcloud storage buckets create gs://$BUCKET_NAME --project=$PROJECT_ID --location=$LOCATION --uniform-bucket-level-access
gcloud storage buckets add-iam-policy-binding gs://$BUCKET_NAME --member=$PRINCIPAL --role=roles/storage.objectUser
```

For a bucket in another project, also grant access to the service agent:

```shell
gcloud storage buckets add-iam-policy-binding gs://$BUCKET_NAME \
    --member=serviceAccount:service-$PROJECT_NUMBER@gcp-sa-devicerun.iam.gserviceaccount.com \
    --role=roles/storage.objectUser
```

See [Share Cloud Storage with Device Run][share-storage] for details. When the service agent cannot read the inputs, the session
fails to start.

The plugin uses fixed paths in the bucket, so several projects and builds can share one bucket:

- `automation/inputs/<sha256>/<file name>`: uploaded APKs and pushed files. Identical files share one object, and an existing
  object of the same size is not uploaded again.
- `automation/sessions/<session id>/`: session outputs, nested by job and execution.
- `automation/smart-sharding/<record name>.yaml`: the timing records of [smart sharding](#sharding).

Objects accumulate with every test run, so add a lifecycle rule that deletes old ones, such as this `lifecycle.json`:

```json
{
  "rule": [
    {
      "action": {"type": "Delete"},
      "condition": {"age": 30}
    }
  ]
}
```

```shell
gcloud storage buckets update gs://$BUCKET_NAME --lifecycle-file=lifecycle.json
```

The plugin uploads deleted inputs again. Device Run rewrites a timing record after each smart-sharded run, so the rule deletes only
records that have not been updated within its age, and the next run then creates a new record without timing history.

## Authentication

The plugin uses [Application Default Credentials][adc] (ADC) for the whole build and looks for credentials in this order:

1. The file that the `GOOGLE_APPLICATION_CREDENTIALS` environment variable points to: a service account key, user credentials, or a
   Workload or Workforce Identity Federation configuration. The build fails when the variable names a missing file.
2. The local credentials of the gcloud CLI, for a user account or an impersonated service account.
3. The attached service account of a Google Cloud runtime, such as a Compute Engine VM.

For local development, log in with your user account and select the Device Run project as the quota project:

```shell
gcloud auth application-default login
gcloud auth application-default set-quota-project $PROJECT_ID
```

To run the build as a service account instead, add `--impersonate-service-account=$SERVICE_ACCOUNT_EMAIL` to the login command. Your
account then needs `roles/iam.serviceAccountTokenCreator` on the service account.

On CI, prefer Workload Identity Federation over service account keys, for example with the `google-github-actions/auth` action on
GitHub Actions, which writes a credentials configuration and sets `GOOGLE_APPLICATION_CREDENTIALS`. Federated credentials might not
name a project, so set `GOOGLE_CLOUD_QUOTA_PROJECT` too:

```shell
GOOGLE_APPLICATION_CREDENTIALS=/path/to/credentials.json GOOGLE_CLOUD_QUOTA_PROJECT=$PROJECT_ID ./gradlew smallPhoneDebugAndroidTest
```

A service account key includes the project of the service account, which runs the sessions unless `GOOGLE_CLOUD_QUOTA_PROJECT`
selects another one. When the credentials name no project, the setup and test tasks fail with `Unable to determine the Google Cloud
project for Device Run from Application Default Credentials`.

The plugin loads the credentials once per build, so a Gradle daemon uses the credentials of the current invocation.

## Configuration

This example sets most options of the `deviceRun` extension:

```kotlin
import io.technoirlab.device.run.gradle.api.CapturePolicy
import io.technoirlab.device.run.gradle.api.DeviceOrientation
import io.technoirlab.device.run.gradle.api.RetryScope
import java.time.Duration

deviceRun {
    devices {
        register("smallPhone") {
            deviceId = "smallphone-arm-35" // Catalog device, which selects the device model and Android version. Required.
            locale = "en-US" // Device locale. Unset keeps the default locale of the device.
            orientation = DeviceOrientation.PORTRAIT // Device orientation. Unset keeps the default orientation of the device.
        }
    }

    instrumentationTimeout = Duration.ofMinutes(20) // Limit of one instrumentation run, 1 minute to 3 hours. Defaults to 5 minutes.
    waitTimeout = Duration.ofHours(1) // Limit of the whole test task. Unset waits until the session finishes.
    labels.put("suite", "instrumentation") // Label of the tests in Device Run, returned in the session report.

    sharding { // Defaults to none(), a single shard.
        smart {
            targetDuration = Duration.ofMinutes(5) // Target duration of each shard. Required.
            maxShardCount = 4 // Maximum number of shards. Unset uses the shard limit of the device.
        }
    }

    retries {
        maxAttempts = 3 // Total number of attempts, including the first one. Defaults to 1, which disables retries.
        parallel = true // Whether the retries of a failed attempt run at the same time. Defaults to false.
        scope = RetryScope.FAILED_TEST_CASES // Which tests a retry runs. Defaults to RetryScope.SHARD.
    }

    capture {
        logcat = CapturePolicy.ALWAYS // When the device log is kept. Defaults to CapturePolicy.ON_FAILURE.
        video = CapturePolicy.ON_FAILURE // When screen recordings are kept. Defaults to CapturePolicy.DISABLED.
    }

    files {
        push {
            register("fixtures") {
                localFile = layout.projectDirectory.file("fixtures/data.json") // Local file, uploaded to the bucket.
                destinationPath = "/sdcard/data.json" // Absolute path of the file on the device. Required.
            }
        }
        pathsToPull.add("/sdcard/test-output") // Absolute device path, downloaded after the tests.
    }

    storage {
        bucket = "my-device-run-bucket" // Existing bucket for test inputs and outputs, without gs://. Required.
    }
}
```

The plugin validates the options before it uploads anything.

### Devices

`deviceId` is required and names a device of the Device Run catalog, which selects a device model and its Android version. List the
Android devices that your project can use with:

```shell
gcloud beta device-run devices list --filter="platform=ANDROID"
```

`locale`, in the form `language-region`, and `orientation` set up the device before the tests run.

The setup task of each device, `<device>Setup`, looks the device up in the catalog and records its API level, ABIs, and screen
density, which AGP uses to select the APKs of the tested application. A test task fails before its session starts when the device runs
an API level below the `minSdk` of the tests.

### Test selection and runner arguments

The tests run with the instrumentation runner arguments that AGP collects for the variant. Set arguments for every variant in
`defaultConfig`, and for some variants with the variant API:

```kotlin
android {
    defaultConfig {
        testInstrumentationRunnerArguments["notAnnotation"] = "androidx.test.filters.FlakyTest"
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.androidTest?.instrumentationRunnerArguments?.put("notPackage", "com.example.premium")
    }
}
```

Select tests with the arguments `package`, `notPackage`, `class`, `notClass`, `annotation`, `notAnnotation`, and `size`, which take
comma-separated values. Device Run does not support `testFile` and `notTestFile`.

`android.testInstrumentationRunnerArguments.<key>` Gradle properties set arguments for a single invocation:

```shell
./gradlew smallPhoneDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.size=large
```

### Android Test Orchestrator

The tests run with Android Test Orchestrator when the AGP test execution option selects it, as local tests do:

```kotlin
android {
    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }
}

dependencies {
    androidTestUtil("androidx.test:orchestrator:1.6.1")
}
```

Device Run installs its own orchestrator in the version of `androidx.test:orchestrator` that `androidTestUtil` resolves to, or in its
default version without that dependency. A test task fails before anything is uploaded when Device Run does not offer the version.
As in AGP, `ANDROID_TEST_ORCHESTRATOR` selects the AndroidX orchestrator too. Orchestrator options such as `clearPackageData` are
instrumentation runner arguments. List the orchestrator versions that Device Run offers with:

```shell
gcloud beta device-run software-versions list --filter="softwareType=ANDROIDX_TEST_ORCHESTRATOR"
```

Device Run installs its own test services for every test run and picks their version itself, so
`androidx.test.services:test-services` in `androidTestUtil` has no effect. Test storage and `clearPackageData` work with them.

### Timeouts and labels

`instrumentationTimeout` limits one instrumentation run, between 1 minute and 3 hours. `waitTimeout` limits the whole test task,
including queueing, device setup, test execution, and retries; when it expires, the plugin
[cancels the session](#cancelling-sessions) and the test task fails.

`labels` adds at most 13 labels to the tests in Device Run, with keys of at most 32 bytes and values of at most 1024 bytes. Device Run
returns them in the session report; the test results do not include them. The plugin adds the labels `project`, `variant`, and
`device` itself, so `labels` must not use these keys.

### Sharding

`sharding` selects how Device Run splits the tests into shards; the last selection applies. Each shard runs on its own device and is
billed separately. Physical devices allow at most 20 shards and virtual devices at most 200. Instrumentation runner arguments must not
set `numShards` or `shardIndex`.

`uniform` splits the tests into `shardCount` shards with about the same number of tests. The shard count must not exceed the number of
tests:

```kotlin
deviceRun {
    sharding {
        uniform {
            shardCount = 4
        }
    }
}
```

`smart` splits the tests into shards of about `targetDuration`, between 2 minutes and 3 hours, based on the test durations of earlier
runs. `maxShardCount` optionally limits the number of shards. Shards can take longer than the target, so keep it well below
`instrumentationTimeout`. Shards run their tests by name, so tests that JUnit ignores, such as those annotated with `@Ignore`, are not
reported.

Smart sharding plans the shards from a timing record in the bucket. When the tests finish, Device Run adds their durations to the
record and keeps the earlier ones, including those of tests that did not run. By default, the record name combines the root project
name, project path, variant, and catalog device, so each test suite keeps its own record, and checkouts on other paths, branches, and
machines that use the same bucket share it. Set `rootProject.name` in the settings: Gradle otherwise names the root project after its
directory, so a checkout in a directory with another name, such as a CI workspace, starts without timing history.

Builds that run at the same time, such as CI jobs of different branches, can share a record: Device Run adds the durations of each
job to the latest version of the record, so concurrent jobs keep each other's durations. `smart.timingRecordName`, a name of letters,
digits, `.`, `_`, and `-`, replaces the default record name.

### Retries

`retries.maxAttempts` is the total number of attempts, including the first one, between 1 and 5. A test that fails in some attempts
and passes in others is reported as passed and marked as flaky. `scope` selects what a retry runs: `SHARD` reruns every test of the
failed shard, and `FAILED_TEST_CASES` reruns only the failed tests. `parallel` runs the retries of a failed attempt at the same time on
separate devices.

With `SHARD`, Device Run fails a shard unless one attempt passes all of its tests, even when every test passed in some attempt. The
test task then passes and warns that Device Run failed the session, as described under [Test results](#test-results).
`FAILED_TEST_CASES` needs at least one test class in the test APK that declares a runner that Device Run supports with `@RunWith`,
such as `AndroidJUnit4` or `Parameterized`; otherwise Device Run silently reruns every test of the shard.

When a test crashes the instrumentation process, the attempt ends, and a `FAILED_TEST_CASES` retry runs only the crashed test, so
the tests after it never run, and the test task fails. With [Android Test Orchestrator](#android-test-orchestrator), each test runs in
its own process, so a crash fails only that test.

Device Run also retries infrastructure failures on its own; these options do not control those retries.

### Files

`files.push` copies at most 50 files to the device before the tests run. Each entry needs an absolute `destinationPath` and exactly
one source:

- `localFile`: a local file, which the plugin uploads to the bucket. A file produced by a task makes the upload depend on that task.
- `gcsUri`: an existing Cloud Storage object, such as `gs://my-bucket/fixtures/data.json`. Both the principal of the build and the
  Device Run service agent need read access to it.

The plugin checks Cloud Storage objects and uploads local files in every build, and test tasks run again when a pushed file changes.

`files.pathsToPull` copies at most 10 files or directories, given as absolute paths, from the device after the tests. They are
downloaded with the other [session outputs](#test-results).

### Capture

`capture.logcat` and `capture.video` select when the device log and the screen recordings are kept: `DISABLED`, `ON_FAILURE`,
or `ALWAYS`.

Device Run cannot discard device logs on its own, so with `ON_FAILURE` the plugin downloads them only when the session does not
pass; they remain in the bucket otherwise. Device Run decides itself which recordings to keep, so with `ON_FAILURE` it can also keep
the recording of a passing run, such as one that passed after a retry.

## Test results

Test tasks write their results to the AGP results directory of the device, such as
`build/outputs/androidTest-results/managedDevice/debug/smallPhone`:

- `TEST-<device>.xml`: the JUnit report with the final outcome of each test, which the AGP HTML report reads.
- `test-result.pb` and `device-info.pb`: the results and the device that Android Studio lists under Import Test Results.
- `device-run/`: the session outputs, with the pulled files of each execution and the device logs and recordings that
  `capture` keeps.
- `device-run-session.json`: the project and name of the session.

Device Run uploads the session outputs in the seconds after the session ends. The plugin downloads them once two listings 5 seconds
apart match and contain the files that the session reports name. After a minute, it downloads the outputs that exist and warns
about the missing ones.

A test task fails when a test fails, when the session ends with an infrastructure error, a timeout, or cancellation, or when the
session reports no tests. It also fails when the instrumentation log of an attempt shows tests that never ran, or failures that the
reports omit, such as a failing `@BeforeClass` under Android Test Orchestrator; the task logs the reason, which `test-result.pb`
lists as a platform error. Tests that ended with an error are reported as failures.

Device Run fails a session when no attempt of a shard passes every test, even when every test passed in some attempt. The test
task of such a session passes, logs a warning, and adds it to `test-result.pb` as an issue, provided that Device Run blames failed
tests and that the reports and instrumentation logs of the attempts confirm that each failed attempt failed tests. Otherwise the
task fails and names what the reports do not confirm, such as an attempt that the session report omits and whose instrumentation
log is missing.

Device Run reports durations but no start times, so in `test-result.pb` a test starts when the shard or retry attempt that ran it
started, or else when the session started.

## Cancelling sessions

The plugin cancels the session of a test task when the session does not finish within `waitTimeout`, when waiting for it fails, or
when the build is cancelled, and then waits up to a minute for Device Run to confirm. Waiting fails when Device Run rejects a status
request or stays unreachable for five minutes; shorter outages only delay the status updates.

When the Gradle process is killed, the session keeps running until it finishes. Until the test task runs again,
`device-run-session.json` names the session, which you can cancel with:

```shell
gcloud beta device-run sessions cancel $SESSION_ID --project=$PROJECT_ID
```

## Troubleshooting

The plugin reports problems through the Gradle Problems API, in the `device-run` group. A failing task prints its problems with
the failure, and the build lists every problem in its problems report, `build/reports/problems/problems-report.html`. Gradle prints
warnings only with `--warning-mode=all`.

| Problem ID                           | Reported when                                                                                 |
|--------------------------------------|-----------------------------------------------------------------------------------------------|
| `device-run:invalid-option`          | An option or instrumentation runner argument is invalid; the task fails with one per problem. |
| `device-run:api-level-below-min-sdk` | The catalog device runs an API level below the `minSdk` of the tests; the test task fails.    |
| `device-run:no-tested-apk`           | AGP selects no APK of the tested application for the catalog device; the test task fails.     |
| `device-run:unsupported-test-option` | The tests configure an AGP test option that Device Run devices do not apply; a warning.       |

When a task fails with a permission error, check the roles in [Google Cloud project](#google-cloud-project) and
[Storage bucket](#storage-bucket), and that ADC selects the expected account and project.

## Limitations

- Only Android instrumentation tests run; iOS devices of the catalog are rejected.
- The Kotlin Multiplatform Android library plugin, `com.android.kotlin.multiplatform.library`, is not supported.
- Helper APKs from `androidTestUtil` other than the orchestrator and test services are not installed, and AGP installation options,
  disabled animations, and test coverage are not applied. The plugin reports a warning when the tests configure them.
- Files written to the AGP additional test output directory are not collected; pull them with `files.pathsToPull` instead.
- The plugin installs only the test APK and the APKs of the tested application that AGP selects for the device.

[adc]: https://cloud.google.com/docs/authentication/application-default-credentials
[device-run]: https://docs.cloud.google.com/developer-device-platform/device-run
[share-storage]: https://docs.cloud.google.com/developer-device-platform/device-run/share-storage

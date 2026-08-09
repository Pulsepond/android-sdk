# Pulsepond Android SDK

`dev.pulsepond:android-sdk` sends explicit, privacy-conscious product events
from Android applications to a self-hosted Pulsepond Worker.

Version `0.1` supports Android API 23 and newer. It does not include automatic
capture, advertising identifiers, device fingerprinting, user profiles, or a
persistent event queue.

## Install

Maven Central publication is not enabled until the `dev.pulsepond` namespace is
approved. To test an unreleased checkout locally:

```sh
./gradlew publishToMavenLocal
```

```kotlin
dependencies {
    implementation("dev.pulsepond:android-sdk:0.1.0")
}
```

Stable GitHub Releases also contain the reviewed AAR, source JAR, and POM.

## Configure

Create one client for a Pulsepond Android source. The default identity exists
only in memory and rotates when the process restarts:

```kotlin
import dev.pulsepond.android.Pulsepond
import dev.pulsepond.android.PulsepondConfig

val pulsepond = Pulsepond.create(
    PulsepondConfig(
        endpoint = "https://events.example.com/v1/batch",
        writeKey = "ppw_v1_...",
        environment = "production",
        appVersion = "1.4.0",
        release = "android@1.4.0",
    ),
)

val eventId = pulsepond.track(
    "view_work",
    mapOf("work_id" to "work_123"),
)
```

`track()` returns the event UUIDv7 or `null` when the bounded in-memory queue
is full. Invalid event names or properties throw `PulsepondValidationException`
before anything is enqueued.

Call `flush()` when the application needs an immediate delivery attempt:

```kotlin
lifecycleScope.launch {
    pulsepond.flush()
}
```

Call `shutdown()` from an application-owned teardown path. It makes one final
bounded delivery attempt and permanently closes the client. The SDK deliberately
does not register an Activity lifecycle observer or WorkManager job.

## Identity persistence

Persistent random installation and session IDs require an explicit application
context and application-owned namespace:

```kotlin
import dev.pulsepond.android.PulsepondIdentityPersistence

val pulsepond = Pulsepond.create(
    applicationContext,
    PulsepondConfig(
        endpoint = "https://events.example.com/v1/batch",
        writeKey = "ppw_v1_...",
        environment = "production",
        persistence = PulsepondIdentityPersistence.DeviceStorage(
            namespace = "museum_android",
        ),
    ),
)
```

Only random installation/session UUIDs and the last session activity time are
stored in the application's `noBackupFilesDir`, which Android excludes from
Auto Backup. Pending event payloads are never persisted. A session rotates
after 30 minutes of inactivity; `reset()` discards unsent events and rotates
both IDs. If storage becomes unavailable, the client falls back to memory and
emits one redacted `STORAGE_UNAVAILABLE` diagnostic.

Persistent random identifiers and the word "anonymous" are not a compliance
guarantee. The application owner remains responsible for disclosure, consent,
event design, and regional requirements.

## Event contract

The SDK sends the closed Pulsepond v1 envelope and generates these fields when
`track()` is called:

- lowercase UUIDv7 `event_id`
- `schema_version: 1`
- UTC `occurred_at`
- `platform: "android"`
- configured application and environment fields
- random installation and session UUIDs
- a defensive serialized copy of the explicit properties

Event and property names are ASCII slugs. Properties are flat and limited to
32 values. Values can only be `null`, booleans, JavaScript-safe integer types,
or trimmed printable ASCII strings up to 256 characters. The Worker remains
authoritative for event/property allowlists and optional PII heuristics.

## Delivery behavior

- Requests go only to the configured exact `/v1/batch` URL.
- The SDK explicitly sets only `Authorization` and `Content-Type` headers.
- Batches are bounded by event count and 60,000 serialized UTF-8 bytes.
- `202 Accepted` means the Worker accepted the batch into its Queue; it does
  not promise immediate D1 visibility.
- Network failures, timeouts, `408`, `429`, and `5xx` receive five bounded
  retries with full jitter. `Retry-After` is honored up to 30 seconds.
- A multi-event `413` response is split without changing event IDs. A
  single-event `413` is terminal.
- Unsent events older than 23 hours are dropped before batching by default.

Delivery is asynchronous and best-effort. Events can be dropped by explicit
queue, age, retry, or lifecycle bounds, and ambiguous network failures can
produce duplicates. Do not depend on strict ordering or exactly-once delivery.

Use a dedicated Android Source with `origin_mode: "forbidden"`; normal Android
requests do not carry a browser `Origin`. The source write key is publishable
and grants ingestion only. It must never grant analytics reads or administrative
access. Event/property allowlists, rate limits, rotation, and revocation remain
required server-side controls.

## Privacy

The SDK does not read or send URLs, screen names, application headers or
Cookies, Android advertising ID, Android ID, device model, locale, IP address as
an event property, user identity, search text, feedback bodies, or request
bodies. The Android networking stack still supplies normal HTTP metadata to the
collector; the collector must not copy it into analytics events.

Diagnostics contain only a stable code, retryability, an optional HTTP status,
and a dropped-event count. They never contain write keys, event bodies, names,
or property values.

## Configuration

| Option | Default | Notes |
| --- | --- | --- |
| `endpoint` | required | HTTPS URL with the exact `/v1/batch` path; HTTP is allowed only on localhost |
| `writeKey` | required | Publishable `ppw_v1_...` source credential |
| `environment` | required | ASCII slug, up to 32 characters |
| `appVersion` | omitted | Trimmed printable ASCII, up to 64 characters |
| `release` | omitted | Trimmed printable ASCII, up to 128 characters |
| `persistence` | `Memory` | DeviceStorage requires the Context factory and an explicit namespace |
| `batchSize` | `20` | Between 1 and the protocol maximum of 100 |
| `flushIntervalMs` | `5000` | `0` disables timed flushes |
| `maxQueueSize` | `1000` | In-memory event-count bound |
| `eventTtlMs` | 23 hours | Align with the Source maximum event age |
| `onDiagnostic` | omitted | Receives redacted lifecycle and delivery status |

## Development

Requirements:

- JDK 17
- Android SDK Platform 37 and Build Tools 36

```sh
./gradlew check lint publishToMavenLocal
```

The quality gate runs protocol, queue, retry, identity, real JVM HTTP, lint,
AAR, source JAR, and POM checks. Pull requests and pushes to `main` run the same
gate with a read-only GitHub token.

## Release

Create a stable GitHub Release whose tag matches `v<pulsepond module version>`.
The release workflow reruns the full quality gate and uploads the AAR, source
JAR, and POM. Maven Central publication will be added only after namespace and
signing credentials are configured; release code does not contain placeholder
or long-lived secrets.

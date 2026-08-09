# Contributing

Use JDK 17 and the checked-in Gradle wrapper.

```sh
./gradlew check lint publishToMavenLocal
```

Every behavior change must include a deterministic test. Keep browser-independent
Pulsepond v1 protocol behavior aligned with the TypeScript SDK, but do not copy
Android lifecycle, storage, or networking decisions into shared protocol rules.

Do not add automatic capture of screens, URLs, device identifiers, IP addresses,
headers, user identity, search text, feedback bodies, or request bodies. Never
include write keys, event bodies, or property values in logs, errors, diagnostics,
test snapshots, or URLs.

Pull requests should explain user-visible behavior, delivery and privacy
tradeoffs, and the verification performed.


# Contributing

Thanks for contributing to Blackbox.

## Development Setup
1. Install Android Studio (latest stable) and JDK 17.
2. Copy `local.properties.example` to `local.properties` and set `sdk.dir`.
3. Build and test before opening a PR:
   - `./gradlew :app:compileDebugKotlin`
   - `./gradlew :app:testDebugUnitTest`

## Pull Requests
1. Keep PRs focused and small when possible.
2. Include a short summary of behavior changes.
3. Add or update tests when behavior changes.
4. Avoid committing generated files, local SDK paths, or secrets.

## Style
1. Prefer clear, maintainable Kotlin and small composables/functions.
2. Preserve existing architecture and naming conventions unless explicitly refactoring.
3. Keep security/privacy-sensitive behavior explicit and testable.

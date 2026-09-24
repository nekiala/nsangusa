# Backend

Import this directory as a Gradle project in IntelliJ IDEA, use the checked-in Gradle wrapper, and
run `NewsPlatformApplication` with the `local` profile. Gradle resolves the Java 25 compiler
toolchain through the pinned Foojay resolver when it is not installed locally. Do not commit
machine-specific `.idea` or `.iml` files.

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
./gradlew test
./gradlew spotlessCheck
```

Flyway owns schema evolution; Hibernate runs in validation mode. The ordinary test suite uses fake X, AI, image, and email adapters and requires no paid account.

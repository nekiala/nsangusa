# Backend

Import this directory as a Gradle project in IntelliJ IDEA, select JDK 25, and run `NewsPlatformApplication` with the `local` profile. The checked-in Gradle 9.7.1 wrapper provides deterministic command-line and IntelliJ builds.

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
./gradlew test
./gradlew spotlessCheck
```

Flyway owns schema evolution; Hibernate runs in validation mode. The ordinary test suite uses fake X, AI, image, and email adapters and requires no paid account.

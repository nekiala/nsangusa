plugins {
  java
  id("org.springframework.boot") version "4.1.1"
  id("io.spring.dependency-management") version "1.1.7"
  id("com.diffplug.spotless") version "8.10.1"
  id("org.cyclonedx.bom") version "3.0.1"
}

group = "com.nsangusa"
version = "0.1.0"
description = "Event-driven news publishing platform"

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(25)
  }
}

repositories {
  mavenCentral()
}

extra["springModulithVersion"] = "2.1.1"
extra["testcontainersVersion"] = "2.0.5"
extra["awsSdkVersion"] = "2.54.10"

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  implementation("org.springframework.boot:spring-boot-starter-data-redis")
  implementation("org.springframework.boot:spring-boot-starter-flyway")
  implementation("org.springframework.boot:spring-boot-starter-kafka")
  implementation("org.springframework.boot:spring-boot-starter-mail")
  implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
  implementation("org.springframework.boot:spring-boot-starter-session-data-redis")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-webmvc")
  implementation("org.flywaydb:flyway-database-postgresql")
  implementation("org.owasp.encoder:encoder:1.4.0")
  implementation("org.bouncycastle:bcprov-jdk18on:1.82")
  implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0")
  implementation(platform("software.amazon.awssdk:bom:${property("awsSdkVersion")}"))
  implementation("software.amazon.awssdk:s3")
  implementation("org.springframework.modulith:spring-modulith-events-api")
  implementation("org.springframework.modulith:spring-modulith-observability-api")
  implementation("org.springframework.modulith:spring-modulith-starter-core")
  implementation("org.springframework.modulith:spring-modulith-starter-jpa")
  runtimeOnly("org.postgresql:postgresql")
  runtimeOnly("io.micrometer:micrometer-registry-prometheus")
  runtimeOnly("org.springframework.modulith:spring-modulith-actuator")
  runtimeOnly("org.springframework.modulith:spring-modulith-events-kafka")
  runtimeOnly("org.springframework.modulith:spring-modulith-observability-core")
  runtimeOnly("org.springframework.modulith:spring-modulith-runtime")
  testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
  testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
  testImplementation("org.springframework.boot:spring-boot-starter-data-redis-test")
  testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
  testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
  testImplementation("org.springframework.boot:spring-boot-starter-mail-test")
  testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-client-test")
  testImplementation("org.springframework.boot:spring-boot-starter-security-test")
  testImplementation("org.springframework.boot:spring-boot-starter-session-data-redis-test")
  testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
  testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
  testImplementation("org.springframework.modulith:spring-modulith-starter-test")
  testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2")
  testImplementation(platform("org.testcontainers:testcontainers-bom:${property("testcontainersVersion")}"))
  testImplementation("org.testcontainers:testcontainers-junit-jupiter")
  testImplementation("org.testcontainers:testcontainers-postgresql")
  testImplementation("org.testcontainers:testcontainers-kafka")
  testImplementation("org.testcontainers:testcontainers")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
  imports {
    mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
  }
}

tasks.withType<Test> {
  useJUnitPlatform()
}

spotless {
  java {
    googleJavaFormat("1.30.0")
    formatAnnotations()
  }
  kotlinGradle {
    ktlint("1.7.1")
  }
}

configurations.configureEach {
  resolutionStrategy {
    cacheDynamicVersionsFor(0, "seconds")
    cacheChangingModulesFor(0, "seconds")
  }
}

dependencyLocking {
  lockAllConfigurations()
}

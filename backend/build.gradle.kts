import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
    kotlin("plugin.jpa") version "2.4.21"
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "com.ynixt"
version = "0.1.3"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

repositories {
    mavenCentral()
}

ktlint {
    version.set("1.8.0")
}

// ktlint embeds Kotlin 2.2.21; its compiler internals must stay independent of the application BOM.
configurations.matching { it.name.startsWith("ktlint") }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion("2.2.21")
        }
    }
}

sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    }
}

val integrationTestTask =
    tasks.register("integrationTest", Test::class) {
        description = "Runs the integration tests."
        group = "verification"

        testClassesDirs = sourceSets["integrationTest"].output.classesDirs
        classpath = sourceSets["integrationTest"].runtimeClasspath

        shouldRunAfter(tasks.named("test"))
    }

fun loadEnvFile(envFile: File): Map<String, String> {
    if (!envFile.exists()) return emptyMap()
    return envFile
        .readLines()
        .asSequence()
        .map(String::trim)
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val separatorIndex = line.indexOf('=')
            if (separatorIndex <= 0) return@mapNotNull null
            val key = line.substring(0, separatorIndex).trim()
            val value = line.substring(separatorIndex + 1).trim().removeSurrounding("\"")
            key to value
        }.toMap()
}

val integrationEnvFile =
    listOf(
        file("../test.env"),
        file("test.env"),
    ).firstOrNull { it.exists() }

integrationTestTask.configure {
    if (integrationEnvFile != null) {
        environment(loadEnvFile(integrationEnvFile))
    }
}

val integrationTestImplementation =
    configurations.getByName("integrationTestImplementation") {
        extendsFrom(configurations.implementation.get())
        extendsFrom(configurations.testImplementation.get())
    }

configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-session-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.hibernate.orm:hibernate-community-dialects")
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("com.samskivert:jmustache")

    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.mockk:mockk:1.14.12")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")

    integrationTestImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    integrationTestImplementation("org.testcontainers:testcontainers")
    integrationTestImplementation("org.testcontainers:testcontainers-junit-jupiter")
    integrationTestImplementation("com.redis:testcontainers-redis:3.0.1")
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named("build") {
    finalizedBy("integrationTest", "ktlintCheck")
}

springBoot {
    mainClass.set("com.ynixt.dmdl.ApplicationKt")
    buildInfo()
}

tasks.named<BootRun>("bootRun") {
    workingDir = rootProject.projectDir.parentFile
}

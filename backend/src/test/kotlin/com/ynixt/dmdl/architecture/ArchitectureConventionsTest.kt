package com.ynixt.dmdl.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.assertTrue

class ArchitectureConventionsTest {
    @Test
    fun `domain contract packages must contain only interfaces`() {
        val contractRoots =
            listOf(
                projectRoot.resolve("src/main/kotlin/com/ynixt/dmdl/domain/service"),
                projectRoot.resolve("src/main/kotlin/com/ynixt/dmdl/domain/gateway"),
                projectRoot.resolve("src/main/kotlin/com/ynixt/dmdl/domain/queue/producer"),
            )

        val classRegex = Regex("""^\s*(open\s+|abstract\s+|data\s+)?class\s+[A-Za-z0-9_]+""")
        val violations =
            contractRoots.flatMap { root ->
                kotlinFiles(root).flatMap { file ->
                    file.readText().lines().mapIndexedNotNull { index, line ->
                        if (classRegex.containsMatchIn(line)) {
                            "${relative(file)}:${index + 1} -> concrete class in contract package"
                        } else {
                            null
                        }
                    }
                }
            }

        assertTrue(
            violations.isEmpty(),
            "Contract package violations (must be interfaces only):\n${violations.joinToString("\n")}",
        )
    }

    @Test
    fun `domain should not depend on any infrastructure layer`() {
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..resource..", "..application..")
            .because("Domain must be pure — no dependencies on infrastructure or application layers")
            .check(importedClasses)
    }

    @Test
    fun `domain should not depend on Spring framework`() {
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "jakarta.persistence..",
                "jakarta.transaction..",
            ).because("Domain must have zero framework dependencies")
            .check(importedClasses)
    }

    @Test
    fun `resource services should not depend on JPA repositories directly`() {
        noClasses()
            .that()
            .resideInAPackage("..resource.service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..resource.persistence.repository..")
            .because("Resource services must use domain service/gateway interfaces, not JPA repositories directly")
            .check(importedClasses)
    }

    @Test
    fun `resource services should not depend on JPA entities directly`() {
        noClasses()
            .that()
            .resideInAPackage("..resource.service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..resource.persistence.entity..")
            .because("Resource services must work with domain entities — mapping happens in gateway/persistence layer")
            .check(importedClasses)
    }

    @Test
    fun `resource services should not depend on persistence mappers directly`() {
        noClasses()
            .that()
            .resideInAPackage("..resource.service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..resource.persistence.mapper..")
            .because("Resource services must not handle JPA mapping — that belongs in gateway/persistence implementations")
            .check(importedClasses)
    }

    @Test
    fun `resource services should not depend on persistence services directly`() {
        noClasses()
            .that()
            .resideInAPackage("..resource.service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..resource.persistence.service..")
            .because("Resource services must not import persistence service. Instead import repository from domain")
            .check(importedClasses)
    }

    @Test
    fun `resource services should not use Spring web exceptions`() {
        noClasses()
            .that()
            .resideInAPackage("..resource.service..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework.web.server..",
                "org.springframework.http..",
            ).because("Resource services must throw domain exceptions, not HTTP exceptions — the web layer handles mapping")
            .check(importedClasses)
    }

    @Test
    fun `gateway implementations should not be accessed outside their own package and config`() {
        noClasses()
            .that()
            .resideInAPackage("..resource.service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..resource.gateway..")
            .because("Resource services should use domain gateway interfaces, not concrete gateway implementations")
            .check(importedClasses)
    }

    @Test
    fun `domain should not depend on application DTOs`() {
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..application.web.dto..")
            .because("Domain must never know about DTOs — mapping between DTOs and domain entities happens in the application layer")
            .check(importedClasses)
    }

    @Test
    fun `JPA entities should not be used outside resource persistence package`() {
        classes()
            .that()
            .resideInAPackage("..resource.persistence.entity..")
            .should()
            .onlyBeAccessed()
            .byAnyPackage(
                "..resource.persistence..",
            ).because("JPA entities must not leak outside persistence — the rest of the system works with domain entities")
            .check(importedClasses)
    }

    @Test
    fun `cache services should only be accessed by resource services and resource gateway`() {
        classes()
            .that()
            .resideInAPackage("..resource.cache..")
            .should()
            .onlyBeAccessed()
            .byAnyPackage(
                "..resource.service..",
                "..resource.gateway..",
                "..resource.cache..",
            ).because("Redis cache services are infrastructure details — only resource layer implementations should access them")
            .check(importedClasses)
    }

    @Test
    fun `application layer should not depend on resource layer`() {
        noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..resource..")
            .because("Application layer depends on domain interfaces — Spring wires the resource implementations via DI")
            .check(importedClasses)
    }

    @Test
    fun `resource services should not throw generic RuntimeException`() {
        val servicesRoot = projectRoot.resolve("src/main/kotlin/com/ynixt/dmdl/resource/service")
        val forbiddenThrows =
            listOf(
                "throw RuntimeException(",
                "throw IllegalStateException(",
                "throw IllegalArgumentException(",
            )

        val violations =
            kotlinFiles(servicesRoot).flatMap { file ->
                file.readText().lines().mapIndexedNotNull { index, line ->
                    forbiddenThrows.firstOrNull { token -> line.contains(token) }?.let { token ->
                        "${relative(file)}:${index + 1} -> use domain exceptions instead of '$token'"
                    }
                }
            }

        assertTrue(
            violations.isEmpty(),
            "Generic exception violations in resource services:\n${violations.joinToString("\n")}",
        )
    }

    @Test
    fun `jobs should not depend on resource persistence or gateway`() {
        noClasses()
            .that()
            .resideInAPackage("..application.job..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "..resource.persistence..",
                "..resource.gateway..",
                "..resource.cache..",
            ).because(
                "Jobs must only use domain service interfaces — they detect and publish, never download or access infrastructure directly",
            ).check(importedClasses)
    }

    @Test
    fun `persistence services should only depend on JPA repositories, mappers, entities, and domain`() {
        noClasses()
            .that()
            .resideInAPackage("..resource.persistence.service..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "..resource.gateway..",
                "..domain.gateway..",
                "..domain.service..",
                "..resource.service..",
                "..resource.queue..",
                "..resource.cache..",
                "..application..",
            ).because(
                "Persistence services are pure persistence adapters — they only access " +
                    "JPA repositories (..resource.persistence.repository..), " +
                    "JPA entities (..resource.persistence.entity..), " +
                    "mappers (..resource.persistence.mapper..), " +
                    "and domain types. " +
                    "They must NOT access gateways, business services, queues, cache, or application layer.",
            ).check(importedClasses)
    }

    private fun kotlinFiles(root: java.nio.file.Path): List<java.nio.file.Path> =
        if (!java.nio.file.Files
                .exists(root)
        ) {
            emptyList()
        } else {
            root
                .walk()
                .filter {
                    java.nio.file.Files
                        .isRegularFile(it) &&
                        it.toString().endsWith(".kt")
                }.toList()
        }

    private fun relative(path: java.nio.file.Path): String = path.relativeTo(projectRoot).toString().replace('\\', '/')

    private companion object {
        val projectRoot: java.nio.file.Path = Path(".").toAbsolutePath().normalize()

        val importedClasses: JavaClasses =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPackages("com.ynixt.dmdl")
    }
}

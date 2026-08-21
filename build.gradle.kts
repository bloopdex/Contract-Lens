plugins {
    kotlin("jvm") version "2.2.0" apply false
    kotlin("plugin.serialization") version "2.2.0" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2" apply false
    // Coverage gate: aggregate Kover reports across all modules.
    id("org.jetbrains.kotlinx.kover") version "0.9.9" apply false
    // (ADR-007): the fat JAR is the primary release artifact.
    // 9.4.3 is the latest plugin-portal release for Gradle 9 (min 9.0).
    id("com.gradleup.shadow") version "9.4.3" apply false
}

// OSV-scan fixes for the BUILDSRIPT classpath (the subproject
// resolution rules below do not cover it): the ktlint Gradle plugin's
// own graph resolves log4j-api 2.26.0 (GHSA-qv9r-c865-cp47, fixed
// 2.26.1) and commons-lang3 3.17.0 (GHSA-j288-q9x7-2f5v, fixed 3.18.0).
// Build-time only, never shipped — but the patch versions exist, so
// they are lifted instead of waived.
buildscript {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            when {
                requested.group == "org.apache.logging.log4j" &&
                    requested.name == "log4j-api" &&
                    (requested.version?.let { it < "2.26.1" } ?: false) ->
                    useVersion("2.26.1")
                requested.group == "org.apache.commons" &&
                    requested.name == "commons-lang3" &&
                    (requested.version?.let { it < "3.18.0" } ?: false) ->
                    useVersion("3.18.0")
            }
        }
    }
}

allprojects {
    group = "dev.bloopdex.contractlens"
    version = "1.0.1"

    repositories {
        mavenCentral()
    }
}

// The root applies Kover for AGGREGATED reports across all modules
// (the root itself has no Kotlin sources).
apply(plugin = "org.jetbrains.kotlinx.kover")

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "org.jetbrains.kotlinx.kover")

    // Supply-chain discipline: every configuration resolves
    // against a committed gradle.lockfile (regenerate deliberately with
    // `gradlew dependencies --write-locks`, never by hand). Integrity of
    // the resolved artifacts is enforced in CI with
    // `--dependency-verification=strict` against
    // gradle/verification-metadata.xml (regenerate with
    // `gradlew --write-verification-metadata sha256`).
    dependencyLocking {
        lockAllConfigurations()
    }

    // OSV-scan fixes (docs/security.md): lift known-vulnerable
    // dependency versions WITHOUT touching anything already newer.
    //   - jackson-core/databind < 2.21.4 -> 2.21.5: the swagger-parser
    //     2.1.44 graph still pins 2.21.1, which carries several GHSAs
    //     (incl. HIGH GHSA-r7wm-3cxj-wff9 and GHSA-j3rv-43j4-c7qm) —
    //     jackson is ON the untrusted-OpenAPI parse path, so this is a
    //     real fix, not a formality.
    //   - logback-classic/core < 1.5.34 -> 1.5.34: GHSA-25qh-j22f-pwp8
    //     (5.9) and three LOW advisories reach us through the ktlint
    //     plugin's own tool configuration (build-time only, never
    //     shipped); the LOW fixes exist only in the 1.5.x line, so the
    //     ktlint tool configuration is lifted to it (ktlintCheck runs
    //     in every build and would fail loudly on any incompatibility).
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            when {
                requested.group == "com.fasterxml.jackson.core" &&
                    (requested.name == "jackson-core" || requested.name == "jackson-databind") &&
                    (requested.version?.let { it < "2.21.4" } ?: false) ->
                    useVersion("2.21.5")
                requested.group == "ch.qos.logback" &&
                    (requested.name == "logback-classic" || requested.name == "logback-core") &&
                    (requested.version?.let { it < "1.5.34" } ?: false) ->
                    useVersion("1.5.34")
                requested.group == "org.apache.logging.log4j" &&
                    requested.name == "log4j-api" &&
                    (requested.version?.let { it < "2.26.1" } ?: false) ->
                    useVersion("2.26.1")
            }
        }
    }

    // KNOWN, DOCUMENTED WAIVER (SECURITY.md policy): kotlin-gradle-plugin
    // 2.2.0 carries GHSA-r937-wjx7-w2jp (Medium, 6.7) whose published fix
    // is 2.4.20-Beta1 — adopting a beta toolchain as a security reaction
    // is not justified. Reachability: build-time only (compiler plugin),
    // never in the shipped artifact, no untrusted input. Revisit: when a
    // stable Kotlin release containing the fix lands, bump the toolchain
    // deliberately.

    // Pin the ktlint CLI: the plugin's bundled default (1.0.x) ships an
    // old parser that rejects constructs accepted by Kotlin 2.x.
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.6.0")
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            // CI runs on JVM 17 and 21; the bytecode target stays on 17.
            // The toolchain aligns compileJava and compileKotlin targets
            // (the JVM-target validation would otherwise fail the build).
            jvmToolchain(17)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

// Aggregate XML/HTML reports at the root; the
// verification gate is the plugin's koverVerify task with per-module
// rules below (bounds set after measuring the baseline — rationale in
// docs/coverage.md).
extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension>("kover") {
    reports {
        total {
            xml { onCheck = false }
            html { onCheck = false }
        }
    }
}

// Per-module line-coverage minimums, ~4-5 points below the measured
// baseline (2026-08-19): routine refactors stay unblocked, large
// regressions in critical logic fail the gate. The benchmark module
// is a tool and carries no gate.
val coverageBounds =
    mapOf(
        "core" to 85,
        "openapi-parser" to 90,
        "snapshot-store" to 90,
        "registry" to 85,
        "generated-client" to 95,
        "graphql" to 85,
        "json-schema" to 90,
        "cli" to 80,
    )

subprojects {
    val bound = coverageBounds[name]
    if (bound != null) {
        extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension>("kover") {
            reports {
                total {
                    verify {
                        rule {
                            minBound(bound)
                        }
                    }
                }
            }
        }
    }
}

plugins {
    kotlin("jvm") version "2.2.0" apply false
    kotlin("plugin.serialization") version "2.2.0" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2" apply false
    // Phase 5 coverage gate: aggregate Kover reports across all modules.
    id("org.jetbrains.kotlinx.kover") version "0.9.9" apply false
}

allprojects {
    group = "dev.bloopdex.contractlens"
    version = "0.1.0"

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

// Phase 5 coverage: aggregate XML/HTML reports at the root; the
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

plugins {
    kotlin("jvm") version "2.2.0" apply false
    kotlin("plugin.serialization") version "2.2.0" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2" apply false
}

allprojects {
    group = "dev.bloopdex.contractlens"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            compilerOptions {
                // CI runs on JVM 17 and 21; the bytecode target stays on 17.
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                freeCompilerArgs.add("-Xjdk-release=17")
            }
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

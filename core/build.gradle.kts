plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.kotest:kotest-property:5.9.1")
}

plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":core"))

    implementation("io.swagger.parser.v3:swagger-parser:2.1.27")
    implementation("org.yaml:snakeyaml:2.4")

    testImplementation(project(":core"))
    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
}

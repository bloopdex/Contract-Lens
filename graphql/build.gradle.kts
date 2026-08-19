plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":core"))

    // ADR-005 philosophy: mature parsing library over hand-rolled parsers.
    // Only the SDL parser surface (graphql.schema.idl) is used; the
    // canonical model remains ContractLens's own.
    implementation("com.graphql-java:graphql-java:26.0")

    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
}

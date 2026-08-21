# ContractLens container (ADR-007) — the fat JAR on a JRE 17 base, for
# CI/container use. The primary install path is the release JAR; this
# image exists because the delivery plan requires "the Docker image runs
# compare".
#
# Build:
#   docker build -t contractlens:1.0.1 .
# Run:
#   docker run --rm -v "$PWD:/work" -w /work contractlens:1.0.1 diff \
#       old.snapshot.json new.snapshot.json --classify

# syntax=docker/dockerfile:1
FROM gradle:9.7.0-jdk17 AS build
WORKDIR /src
COPY . .
RUN gradle :cli:shadowJar --no-daemon --console=plain

FROM eclipse-temurin:17-jre
WORKDIR /opt/contractlens
COPY --from=build /src/cli/build/libs/contractlens-*-all.jar /opt/contractlens/contractlens.jar
ENTRYPOINT ["java", "-jar", "/opt/contractlens/contractlens.jar"]

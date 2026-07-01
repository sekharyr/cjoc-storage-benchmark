# Layer 2 container image for the workload app. Copied into workload/ during
# the Checkout stage (vars/benchStages.groovy) — this file lives here, not in
# the workload repo, the same way resources/application-iobench.yml does.
#
# Tests are intentionally skipped here: unit-test-${i} and integration-test-${i}
# already ran as their own timed pipeline stages upstream, so re-running them
# during the image build would double-count Maven I/O under the docker-build
# label instead of its own.
#
# Scope note: this image is built, scanned, and pushed by the pipeline, but
# Deploy + soak still runs the unstashed jar directly via `java -jar` — this
# image is a release artifact, not what gets soak-tested.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workload
COPY . .
# The base image sets MAVEN_CONFIG=/root/.m2, which this project's mvnw
# wrapper script wrongly folds into its own argument list (it prepends
# $MAVEN_CONFIG to the CLI args), producing
# "Unknown lifecycle phase /root/.m2" — verified by reproducing this exact
# failure against the real spring-petclinic-rest checkout. Clearing it before
# invoking mvnw is the documented workaround for this Maven Wrapper /
# official-Maven-image incompatibility.
RUN MAVEN_CONFIG="" ./mvnw -B -DskipTests package

# Jar name assumes the spring-petclinic-rest pom's default artifactId/version
# (spring-petclinic-rest-4.0.2.jar) — verify against target/*.jar if this
# breaks after a version bump.
FROM eclipse-temurin:21-jre-jammy
COPY --from=build /workload/target/*.jar /app.jar
EXPOSE 8080
# iobench sets server.port=8080 (application-iobench.yml) — spring-petclinic-rest's
# own default is port 9966 under /petclinic. h2,spring-data-jpa is the
# project's own default repository/DB profile combo (see its readme.md) —
# spring.profiles.active replaces rather than adds to defaults, so iobench
# alone isn't enough; without a repository profile the app fails to start
# (no PetRepository bean). Baked into the entrypoint so the pushed image is
# self-consistent with the EXPOSE above; Deploy + soak still runs the
# unstashed jar directly rather than this image, but this image should still
# be correctly runnable on its own if someone pulls it from Docker Hub.
ENTRYPOINT ["java", "-jar", "/app.jar", "--spring.profiles.active=iobench,h2,spring-data-jpa"]

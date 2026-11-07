plugins {
    id("java")
    // Local Velocity proxy for manual tests (./gradlew runVelocity), like run-paper in Atlas.
    id("xyz.jpenilla.run-velocity") version "3.0.2"
}

group = "org.minecraft"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Velocity 4.x API (Adventure 5, Java 25). The annotation processor generates
    // velocity-plugin.json from the @Plugin annotation on HatlasProxy.
    compileOnly("com.velocitypowered:velocity-api:4.3.1")
    annotationProcessor("com.velocitypowered:velocity-api:4.3.1")
}

java {
    // Velocity 4.x requires Java 25.
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 25
    }

    runVelocity {
        velocityVersion("4.3.1")
    }
}

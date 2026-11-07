plugins {
    id("java")
    // Local Velocity proxy for manual tests (./gradlew runVelocity), like run-paper in Hatlas.
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

    // Unit tests (run by `./gradlew build` and CI). The Velocity API brings Configurate (YAML).
    testImplementation("com.velocitypowered:velocity-api:4.3.1")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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

    test {
        useJUnitPlatform()
    }

    runVelocity {
        velocityVersion("4.3.1")
    }
}

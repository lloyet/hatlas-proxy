plugins {
    id("java")
    // Bundles HikariCP and the PostgreSQL driver in the plugin jar, relocated (Open Question 1).
    id("com.gradleup.shadow") version "9.6.1"
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

    // Database: plain JDBC + HikariCP, PostgreSQL driver bundled (Open Question 1).
    // SLF4J is provided by Velocity, so it is not bundled.
    implementation("com.zaxxer:HikariCP:7.1.0") {
        exclude(group = "org.slf4j")
    }
    implementation("org.postgresql:postgresql:42.7.14") {
        exclude(group = "org.checkerframework") // compile-time annotations only
    }

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

    // The thin jar is not a usable plugin (its libraries are missing): keep it out of the way.
    jar {
        archiveClassifier = "plain"
    }

    // The plugin jar: build/libs/hatlas.proxy-1.0-SNAPSHOT.jar.
    shadowJar {
        archiveClassifier = ""
        relocate("com.zaxxer.hikari", "org.minecraft.hatlas.proxy.lib.hikari")
        relocate("org.postgresql", "org.minecraft.hatlas.proxy.lib.postgresql")
        // The driver is created directly (PGSimpleDataSource), never through DriverManager.
        exclude("META-INF/services/java.sql.Driver")
        exclude("META-INF/versions/*/module-info.class", "module-info.class")
    }

    assemble {
        dependsOn(shadowJar)
    }

    runVelocity {
        velocityVersion("4.3.1")
    }
}

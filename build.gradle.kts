plugins {
    id("java")
    id("application")
    id("com.gradleup.shadow") version "9.0.0-beta15"
    id("com.diffplug.spotless") version "7.0.3"
}

group = "de.rhm176.silk"
version = property("version")!!

val manifestAttributes = mapOf(
    "Main-Class" to "de.rhm176.silk.installer.Main",
    "Implementation-Version" to project.version.toString(),
    "Implementation-Title" to project.name,
)

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.jetbrains:annotations:${project.property("annotationsVersion")}")

    implementation("com.github.tonivade:minimal-json:${project.property("jsonVersion")}")

    testImplementation("uk.org.webcompere:system-stubs-core:${project.property("systemStubsVersion")}")
    testImplementation("uk.org.webcompere:system-stubs-jupiter:${project.property("systemStubsVersion")}")

    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testImplementation("org.assertj:assertj-swing-junit:${project.property("assertjVersion")}")

    testImplementation("com.google.jimfs:jimfs:${project.property("jimfsVersion")}")
    testImplementation("org.awaitility:awaitility:${project.property("awaitilityVersion")}")

    testImplementation(platform("org.junit:junit-bom:${project.property("junitVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation("org.mockito:mockito-core:${project.property("mockitoVersion")}")
    testImplementation("org.mockito:mockito-junit-jupiter:${project.property("mockitoVersion")}")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set(manifestAttributes["Main-Class"])
}

spotless {
    java {
        licenseHeaderFile(file("HEADER"))

        importOrder()
        removeUnusedImports()

        palantirJavaFormat("2.66.0")
    }
}

tasks.jar {
    manifest {
        attributes(manifestAttributes)
    }
}

tasks.shadowJar {
    archiveClassifier.set("fat")

    manifest {
        attributes(manifestAttributes)
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
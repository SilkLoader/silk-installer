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
    implementation("org.json:json:${property("jsonVersion")}")
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
import org.gradle.api.tasks.bundling.Zip
import java.time.LocalDate

plugins {
    java
    application
    checkstyle
    id("info.solidsoft.pitest") version "1.15.0"
}

group = "com.audioengine"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven { url = uri("https://mvn.0110.be/releases") }
}

dependencies {
    implementation("be.tarsos.dsp:core:2.5")
    implementation("be.tarsos.dsp:jvm:2.5")
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.1")
    testImplementation("org.junit.platform:junit-platform-suite-api:1.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-suite-engine:1.10.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.11.0")
    testImplementation("org.assertj:assertj-core:3.25.3")
}

application {
    mainClass.set("Main")
}

apply<SearchEngineValidation>()

tasks.register<Zip>("backupSourceCode") {
    group = "maintenance"
    description = "Creates a ZIP backup of the source code"

    from("src") {
        into("src")
    }
    from("build.gradle.kts")

    archiveFileName.set("src-backup-${LocalDate.now()}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("backups"))

    doFirst {
        println("Starting source code backup...")
    }

    doLast {
        println("Backup created: build/backups/${archiveFileName.get()}")
    }
}

tasks.named("compileJava") {
    dependsOn("backupSourceCode")
}

tasks.named("generateIndexManifest") {
    mustRunAfter("verifyIndexEncoding")
}

tasks.named("build") {
    dependsOn("verifyIndexEncoding", "generateIndexManifest")
}

tasks.test{
    useJUnitPlatform()
    testLogging{
        events("passed", "skipped", "failed")
    }
}

checkstyle {
    toolVersion = "10.21.0"
    isIgnoreFailures = true
}

pitest {
    junit5PluginVersion.set("1.2.1")
    targetClasses.set(setOf("com.audioengine.*"))
}
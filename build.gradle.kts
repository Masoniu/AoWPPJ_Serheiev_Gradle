plugins {
    java
    application
}

group = "com.audioengine"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven {
        url = uri("https://mvn.0110.be/releases")
    }
}

dependencies {
    implementation("be.tarsos.dsp:core:2.5")
    implementation("be.tarsos.dsp:jvm:2.5")
}

application {
    mainClass.set("Main")
}
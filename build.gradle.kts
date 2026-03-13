plugins {
    kotlin("jvm") version "2.3.10"
    application
}

application {
    mainClass.set("com.mrpowergamerbr.butterscotchpreprocessor.ButterscotchPreprocessor")
}

group = "com.mrpowergamerbr.butterscotchpreprocessor"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
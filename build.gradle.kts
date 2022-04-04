import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.6.10"
    kotlin("plugin.serialization") version "1.6.10"
    application
}

group = "info.benjaminhill.radio"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven { setUrl("https://jitpack.io") }
}

dependencies {
    implementation("com.github.salamanders:utils:9b2e054e5d")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.6.20-RC")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.6.0")
   implementation("org.jetbrains.kotlin:kotlin-reflect:1.6.10")
    implementation("org.apache.commons:commons-lang3:3.12.0")
    implementation("io.github.microutils:kotlin-logging:2.1.21")
    implementation("com.google.guava:guava:31.1-jre")
    implementation("com.google.code.gson:gson:2.9.0")
    implementation("com.xenomachina:kotlin-argparser:2.0.7")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin.sourceSets.all {
    languageSettings.optIn("kotlin.RequiresOptIn")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "11"
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = "info.benjaminhill.fm.chopper.MainKt"
    }
}

application {
    mainClass.set("info.benjaminhill.fm.chopper.MainKt")
}
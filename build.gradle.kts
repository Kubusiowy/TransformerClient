plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    id("org.jetbrains.compose") version "1.6.10"
}

group = "com.client"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    google()
    maven { url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev") }
    maven { url = uri("https://maven.mangoautomation.net/repository/ias-release/") }
}

dependencies {
    testImplementation(kotlin("test"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("io.ktor:ktor-client-websockets:2.3.12")

    implementation("com.infiniteautomation:modbus4j:3.1.0")
    implementation("com.fazecast:jSerialComm:2.11.0")
    implementation("org.slf4j:slf4j-simple:2.0.13")
}

kotlin {
    jvmToolchain(21)
}

compose.desktop {
    application {
        mainClass = "com.client.AppKt"
    }
}


tasks.test {
    useJUnitPlatform()
}

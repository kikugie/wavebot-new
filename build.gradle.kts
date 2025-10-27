import dev.kordex.gradle.plugins.kordex.DataCollection

plugins {
    application

    alias(common.plugins.kotlin.jvm)
    alias(common.plugins.kotlin.serialization)

    alias(libs.plugins.kordex.docker)
    alias(libs.plugins.kordex.plugin)
    alias(libs.plugins.ksp.plugin)
}

group = "dev.kikugie.wavebot"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("reflect"))
    implementation(common.misc.commons)
    implementation(common.misc.mordant)
    implementation(common.kotlin.stdlib)

    implementation(common.kotlin.coroutines)
    implementation(common.ktor.client)
    implementation(common.ktor.client.cio)
    implementation(common.ktor.client.logging)
    implementation(common.ktor.client.encoding)
    implementation(common.ktor.client.resources)
    implementation(common.ktor.client.negotiation)
    implementation(common.ktor.json)

    implementation(libs.bundles.logging)
}

kordEx {
    // https://github.com/gradle/gradle/issues/31383
    kordExVersion = libs.versions.kordex.asProvider()

    bot {
        // See https://docs.kordex.dev/data-collection.html
        dataCollection(DataCollection.None)

        mainClass = "dev.kikugie.wavebot.MainKt"
    }

    i18n {
        classPackage = "dev.kikugie.wavebot.i18n"
        translationBundle = "wavebot.strings"
    }
}

application {
    mainClass = "dev.kikugie.wavebot.MainKt"
}
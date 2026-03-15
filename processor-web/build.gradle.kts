plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    js {
        browser()
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":processor-common"))
            implementation("net.perfectdreams.compose.htmldreams:html-core:1.9.0-beta1-v2")
            implementation(compose.runtime)
            implementation(libs.kotlinWrappers.browser)
            implementation(libs.kotlinWrappers.js)

        }
    }
}

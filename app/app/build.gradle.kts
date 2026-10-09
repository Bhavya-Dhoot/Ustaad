plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.swarmos.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.swarmos.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // `-PidSuffix=.x` installs a second copy side by side (com.swarmos.app.x)
        providers.gradleProperty("idSuffix").orNull?.let { applicationIdSuffix = it }
        // `-PportOffset=N` shifts the node API / tput ports so variants can run side by side
        val portOffset = providers.gradleProperty("portOffset").orNull?.toInt() ?: 0
        buildConfigField("int", "PORT_OFFSET", "$portOffset")
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // native executables ship as lib*.so and are exec'd from nativeLibraryDir
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/DEPENDENCIES",
                "/META-INF/io.netty.versions.properties",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.double.receive) // needed: signature check reads the body before the route handler does
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.slf4j.android) // Ktor logs through slf4j; without a binding every server error is silent

    implementation(libs.zxing.core)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.mlkit.text)
    implementation(libs.pdfbox.android)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.datastore.preferences)

    testImplementation(libs.junit)
}

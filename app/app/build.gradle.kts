plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.swarmos.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.swarmos.app"
        minSdk = 26
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
        // Demo build: not debuggable, so BuildConfig.DEBUG is false (no debug routes, random pairing secret per install).
        // Signed with the debug key so it installs over adb; application id com.ustaad.app (androidComponents below).
        create("demo") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
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

androidComponents {
    onVariants(selector().withBuildType("demo")) { variant ->
        variant.applicationId.set("com.ustaad.app") // the Kotlin namespace stays com.swarmos.app
    }
}

// Hands app functions (appfn/): the compiler writes the entry-point service and the function description from the KDoc
ksp {
    arg("appfunctions:aggregateAppFunctions", "true")
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
    implementation(libs.mlkit.text.devanagari)
    implementation(libs.pdfbox.android)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.datastore.preferences)
    implementation(libs.androidx.appfunctions)
    ksp(libs.androidx.appfunctions.compiler)

    testImplementation(libs.junit)
}

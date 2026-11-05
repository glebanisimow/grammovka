import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/** Release signing secrets: local.properties first, then environment. Never committed. */
fun secret(propKey: String, envKey: String): String? =
    localProps.getProperty(propKey)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }

// Must strictly increase for every APK installed over a previous one: minutes since 2026-01-01 UTC,
// so local and CI builds share one monotonic sequence. -PversionCode=<n> overrides it.
val appVersionCode: Int = providers.gradleProperty("versionCode").orNull?.toInt()
    ?: ((System.currentTimeMillis() / 1000L - 1_767_225_600L) / 60L).toInt()
val appVersionName = "0.1.0"

val releaseStoreFile = secret("release.storeFile", "GRAMMOVKA_KEYSTORE_FILE")
val hasReleaseSigning = releaseStoreFile != null && file(releaseStoreFile).exists()

android {
    namespace = "app.grammovka"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.grammovka"
        minSdk = 28
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = secret("release.storePassword", "GRAMMOVKA_KEYSTORE_PASSWORD")
                keyAlias = secret("release.keyAlias", "GRAMMOVKA_KEY_ALIAS")
                keyPassword = secret("release.keyPassword", "GRAMMOVKA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
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

    // Exported Room schemas feed MigrationTestHelper in instrumented tests.
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")

    androidResources {
        localeFilters += listOf("ru")
    }

    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin")
    }
}

if (!hasReleaseSigning) {
    logger.warn("grammovka: release keystore not configured (local.properties release.* or GRAMMOVKA_KEYSTORE_*); release APK will be unsigned")
}

room {
    schemaDirectory("$projectDir/schemas")
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    implementation(libs.camerax.camera2)
    implementation(libs.work.runtime)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.health.connect)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.room.testing)
}

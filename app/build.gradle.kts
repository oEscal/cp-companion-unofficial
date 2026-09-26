plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}


val releaseStorePath = providers.environmentVariable("CP_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("CP_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("CP_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("CP_RELEASE_KEY_PASSWORD").orNull
val releaseCredentials = listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
require(releaseCredentials.all { it == null } || releaseCredentials.all { !it.isNullOrBlank() }) {
    "Set all four CP_RELEASE_* signing variables, or leave all four unset."
}

android {
    namespace = "pt.cpcompanion"
    compileSdk = 37

    defaultConfig {
        applicationId = "pt.cpcompanion"
        minSdk = 30
        targetSdk = 37
        versionCode = 9
        versionName = "0.7.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

    }

    signingConfigs {
        if (listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }) {
            create("release") {
                storeFile = file(requireNotNull(releaseStorePath))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.merges += setOf(
            "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*", "META-INF/NOTICE*",
        )
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        disable += "ObsoleteSdkInt"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// Input to the notice generator; paths are local and never shipped in the APK.
tasks.register("exportReleaseDependencies") {
    inputs.files(configurations.named("releaseRuntimeClasspath"))
    val output = layout.buildDirectory.file("reports/release-dependencies.tsv")
    outputs.file(output)
    doLast {
        val artifacts = configurations.getByName("releaseRuntimeClasspath")
            .resolvedConfiguration.resolvedArtifacts
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(artifacts.map {
                "${it.moduleVersion.id}\t${it.file.absolutePath}"
            }.sorted().joinToString("\n", postfix = "\n"))
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.concurrent.futures)
    implementation(libs.androidx.concurrent.futures.ktx)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.room.testing)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

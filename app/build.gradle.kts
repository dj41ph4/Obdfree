plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeystorePath = System.getenv("OBDFREE_KEYSTORE_PATH")
val releaseKeystorePassword = System.getenv("OBDFREE_KEYSTORE_PASSWORD")
val releaseRequested = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

if (releaseRequested && (releaseKeystorePath.isNullOrBlank() || releaseKeystorePassword.isNullOrBlank())) {
    throw GradleException("Release signing secrets are missing. Refusing to build an unsigned OBDfree release.")
}

android {
    namespace = "com.obdfree.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.obdfree.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.0.6"
    }

    signingConfigs {
        create("release") {
            if (!releaseKeystorePath.isNullOrBlank() && !releaseKeystorePassword.isNullOrBlank()) {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = "obdfree-release"
                keyPassword = releaseKeystorePassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Get version from gradle property (set by CI) or fall back to git tag or default
fun getVersionName(): String {
    // First check for property passed by CI
    val propVersion = project.findProperty("VERSION_NAME") as String?
    if (!propVersion.isNullOrBlank()) {
        return propVersion.removePrefix("v")
    }

    // Try to get from git tag
    return try {
        val process = ProcessBuilder("git", "describe", "--tags", "--abbrev=0")
            .redirectErrorStream(true)
            .start()
        val tag = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        if (process.exitValue() == 0 && tag.isNotEmpty()) {
            tag.removePrefix("v")
        } else {
            "1.0.0-dev"
        }
    } catch (e: Exception) {
        "1.0.0-dev"
    }
}

fun getVersionCode(): Int {
    val versionName = getVersionName()
    // Parse version like "1.2.3" -> 10203
    val parts = versionName.split(".").mapNotNull { it.toIntOrNull() }
    return when (parts.size) {
        3 -> parts[0] * 10000 + parts[1] * 100 + parts[2]
        2 -> parts[0] * 10000 + parts[1] * 100
        1 -> parts[0] * 10000
        else -> 1
    }
}

android {
    namespace = "com.amua.audiodownloader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.amua.audiodownloader"
        minSdk = 26
        targetSdk = 34
        versionCode = getVersionCode()
        versionName = getVersionName()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("KEYSTORE_FILE")
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)

    // Lifecycle & ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Nordic BLE Library
    implementation(libs.nordic.ble)
    implementation(libs.nordic.ble.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

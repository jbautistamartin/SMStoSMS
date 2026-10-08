import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.navigation.safeargs)
}

// local.properties no se versiona: solo existe en la máquina de cada desarrollador.
// Si falta, el build debug debe seguir funcionando; lo único que queda deshabilitado
// es la firma de release.
val localProps = Properties().apply {
    val fichero = rootProject.file("local.properties")
    if (fichero.exists()) fichero.inputStream().use { load(it) }
}

/** Claves de firma leídas de local.properties. Null si la clave no está definida. */
val clavesFirma = listOf("KEYSTORE_PATH", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
    .associateWith { localProps[it] as String? }

/** true solo si las cuatro claves están presentes y no vacías. */
val hayFirmaRelease = clavesFirma.values.all { !it.isNullOrBlank() }

android {
    namespace = "com.capicua.smstosms"
    compileSdk = 35

    signingConfigs {
        if (hayFirmaRelease) {
            create("release") {
                storeFile = file(clavesFirma["KEYSTORE_PATH"]!!)
                storePassword = clavesFirma["KEYSTORE_PASSWORD"]
                keyAlias = clavesFirma["KEY_ALIAS"]
                keyPassword = clavesFirma["KEY_PASSWORD"]
            }
        }
    }

    defaultConfig {
        applicationId = "com.capicua.smstosms"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // URL and token are configured at runtime via Settings → DataStore, not build-time.
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Sin claves en local.properties el APK sale sin firmar, pero el build no rompe.
            if (hayFirmaRelease) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            android.applicationVariants.all {
                if (buildType.name == "release") {
                    outputs.all {
                        (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                            ?.outputFileName = "smstosms-${versionName}.apk"
                    }
                }
            }
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // WorkManager
    implementation(libs.work.runtime)
    implementation(libs.kotlinx.coroutines.guava)

    // DataStore
    implementation(libs.datastore.preferences)

    // OkHttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Timber
    implementation(libs.timber)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

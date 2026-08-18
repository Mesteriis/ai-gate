plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.aigate.router"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aigate.router"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    lint {
        // Проверять релизные сборки и падать на новых ошибках; ранее известные
        // проблемы зафиксированы в baseline, чтобы фейлить только регрессии.
        checkReleaseBuilds = true
        abortOnError = true
        warningsAsErrors = false
        baseline = file("lint-baseline.xml")
    }

    // Release signing is env-only; nothing keystore-shaped is committed to the repo.
    val releaseKeystore = file(providers.environmentVariable("AIGATE_KEYSTORE_PATH").getOrElse("aigate.jks"))
    val releaseStorePassword = providers.gradleProperty("AIGATE_STORE_PASSWORD")
        .orElse(providers.environmentVariable("AIGATE_STORE_PASSWORD"))
    val releaseKeyAlias = providers.gradleProperty("AIGATE_KEY_ALIAS")
        .orElse(providers.environmentVariable("AIGATE_KEY_ALIAS"))
    val releaseKeyPassword = providers.gradleProperty("AIGATE_KEY_PASSWORD")
        .orElse(providers.environmentVariable("AIGATE_KEY_PASSWORD"))

    signingConfigs {
        if (releaseKeystore.exists() && releaseStorePassword.isPresent && releaseKeyAlias.isPresent && releaseKeyPassword.isPresent) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
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
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            // Debug builds use the default debug keystore.
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            // Android-заглушки (android.util.Log и т.п.) возвращают дефолты, а не бросают
            // «not mocked» в JVM-тестах.
            isReturnDefaultValues = true
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Ktor Server
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.websockets)

    // Room Database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // OkHttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // WorkManager (плановое резервное копирование + обновление квот)
    implementation(libs.androidx.work.runtime.ktx)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // Real org.json on the JVM test classpath (android.jar ships only a stub that
    // throws "not mocked" in unit tests).
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

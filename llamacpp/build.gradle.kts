plugins {
    id("com.android.library")
}

android {
    namespace = "com.aigate.llamacpp"
    compileSdk = 37

    defaultConfig {
        // Тот же minSdk, что у приложения: модуль включается только там, где
        // библиотека действительно загрузилась, — см. DeviceSupportProbe.
        minSdk = 24

        externalNativeBuild {
            cmake {
                // Собираем только то, что нужно шлюзу: без примеров, тестов,
                // серверов и утилит. Иначе в APK уедут десятки мегабайт кода,
                // который никогда не выполнится.
                arguments += listOf(
                    "-DLLAMA_BUILD_COMMON=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DGGML_VULKAN=OFF",
                    "-DGGML_OPENCL=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
                cppFlags += "-O3"
            }
        }

        ndk {
            // Только arm64: 32-разрядных устройств, которые потянули бы модель
            // на гигабайты, не существует, а вторая архитектура удваивает и
            // время сборки, и размер APK.
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
}

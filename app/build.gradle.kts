plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.ontab.onebase"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.ontab.onebase"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // Бинарь платформы едет в APK как libonebase.so и распаковывается в
    // nativeLibraryDir: с Android 10 исполнять файлы можно только оттуда,
    // и useLegacyPackaging — единственный способ туда попасть.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}

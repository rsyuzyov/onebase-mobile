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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Бинарь платформы едет в APK как libonebase.so и распаковывается в
    // nativeLibraryDir: с Android 10 исполнять файлы можно только оттуда,
    // и useLegacyPackaging — единственный способ туда попасть.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // BuildConfig.DEBUG отличает отладочную сборку от релизной: отладка WebView
    // включается только в debug, иначе к базе на телефоне можно подключиться
    // отладчиком с любого компьютера, к которому подключён телефон.
    buildFeatures {
        buildConfig = true
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
    testImplementation("junit:junit:4.13.2")

    // Инструментальные тесты гоняются против поддельного сервера, а не против
    // бинаря платформы: обёртку проверяем отдельно от того, что она запускает.
    // Поэтому они идут и на x86_64-эмуляторе, где сама платформа падает на
    // seccomp-фильтре Android.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Нажатия делаются настоящими жестами: клик из evaluateJavascript WebView
    // считает навигацией без участия пользователя и помечает её редиректом, что
    // меняет поведение истории. Тест на JS-кликах проверял бы не то приложение.
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

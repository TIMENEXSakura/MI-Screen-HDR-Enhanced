plugins { id("com.android.application") version "9.4.0" }
android {
    namespace = "dev.local.recorderlab"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "dev.local.recorderlab"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-test"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { cppFlags += listOf("-std=c++17", "-Wall", "-Wextra", "-Werror=return-type") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { jniLibs { useLegacyPackaging = false } }
}

val packageBackendModule by tasks.registering(Exec::class) { commandLine("python3", file("package-module.py").absolutePath) }
tasks.named("preBuild") { dependsOn(packageBackendModule) }

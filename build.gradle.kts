plugins {
 id("com.android.application") version "9.4.0"
 id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}
android {
 namespace = "dev.local.miuihdr"
 compileSdk = 37
 buildToolsVersion = "37.0.0"
 defaultConfig { applicationId = "dev.local.miuihdr"; minSdk = 34; targetSdk = 36; versionCode = 152; versionName = "1.5.2"; ndk { abiFilters += "arm64-v8a" } }
 sourceSets.getByName("main") { manifest.srcFile("AndroidManifest.xml"); java.srcDirs("src"); res.srcDirs("res"); assets.srcDirs("assets"); jniLibs.srcDirs("lib"); resources.srcDirs("module-resources") }
 buildFeatures { compose = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 buildTypes { getByName("release") { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
 packaging { jniLibs { useLegacyPackaging = false }; resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}
android.sourceSets.named("main") { kotlin.directories += "src" }
dependencies {
 compileOnly("io.github.libxposed:api:102.0.0")
 implementation(platform("androidx.compose:compose-bom:2026.09.00"))
 implementation("androidx.activity:activity-compose:1.13.0")
 implementation("androidx.compose.material3:material3:1.5.0-alpha28")
 implementation("androidx.compose.foundation:foundation")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.material:material-icons-extended:1.7.8")
 implementation("com.materialkolor:material-kolor:5.0.1")
}

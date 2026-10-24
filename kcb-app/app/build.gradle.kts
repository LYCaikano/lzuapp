import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localPropsFile.inputStream().use(localProps::load)
}
val amapApiKey = localProps.getProperty("AMAP_API_KEY").orEmpty()

android {
    namespace = "li.lyc.lzuapp"
    compileSdk = 36
    defaultConfig {
        applicationId = "li.lyc.lzuapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.3.2"
        manifestPlaceholders += mapOf("AMAP_API_KEY" to amapApiKey)
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // release 沿用 debug 签名：高德定位 key 绑定了该签名 SHA1，换签名会导致定位 key 失效
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      // 开启 BuildConfig：KcbApplication 用 BuildConfig.DEBUG 门控调试日志落盘
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons)
  debugImplementation(libs.androidx.compose.ui.tooling)

  // Network
  implementation(libs.okhttp)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.zxing.core)

  // 地图（查寝签到选点：osmdroid + 高德公开瓦片）
  implementation(libs.osmdroid.android)
  implementation(libs.amap.location)

  // Storage
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)

  // Local tests
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}

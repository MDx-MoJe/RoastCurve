import java.util.Properties

// 版本号手动维护（公开仓库不宜用 commit 数自动版本，避免历史重建后号回退）
// 每次发布：versionCode 自增 1，versionName 按语义版本升级
val appVersionCode = 100
val appVersionName = "1.0.0"

// 读取签名与指纹配置（keystore.properties 本地文件，不进 git；开源用户用 example 模板）
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}


plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.roastcurve.android"
    compileSdk = 34

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.roastcurve.android"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "OFFICIAL_SHA256", "\"${keystoreProperties.getProperty("officialSha256", "")}\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProperties.getProperty("storeFile", "keystore.jks"))
            storePassword = keystoreProperties.getProperty("storePassword", "")
            keyAlias = keystoreProperties.getProperty("keyAlias", "")
            keyPassword = keystoreProperties.getProperty("keyPassword", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "RoastCurve-$appVersionName.apk"
        }
    }
}



dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
}
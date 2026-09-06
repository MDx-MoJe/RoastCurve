import java.util.Properties

// 版本号手动维护（公开仓库不宜用 commit 数自动版本，避免历史重建后号回退）
// 每次发布：versionCode 自增 1，versionName 按语义版本升级
val appVersionCode = 164
val appVersionName = "1.3.26"

// 读取签名与指纹配置（keystore.properties 本地文件，不进 git；开源用户用 example 模板）
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// 是否有完整签名配置（无则 release 降级 debug 签名，保证开源 clone 能出包）
val hasReleaseSigning = keystoreProperties.getProperty("storeFile") != null &&
    keystoreProperties.getProperty("storePassword") != null &&
    keystoreProperties.getProperty("keyAlias") != null &&
    keystoreProperties.getProperty("keyPassword") != null &&
    file(keystoreProperties.getProperty("storeFile", "")).exists()


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
            // 防二次打包第一道防线：R8 混淆/裁剪（SignatureGuard 指纹基于编译期常量，不受 R8 影响）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 无 keystore.properties（开源 clone）→ debug 签名兜底，保证能出包
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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
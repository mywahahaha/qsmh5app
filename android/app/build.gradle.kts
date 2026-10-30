import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val buildProperties = System.getenv("QSM_BUILD_PROPERTIES")?.let { propertiesPath ->
    Properties().apply {
        file(propertiesPath).reader(Charsets.UTF_8).use { load(it) }
    }
}
val gameId = providers.gradleProperty("gameId").orNull?.toIntOrNull()
    ?: buildProperties?.getProperty("gameId")?.toIntOrNull() ?: 0
val gameVersion = providers.gradleProperty("gameVersion").orNull
    ?: buildProperties?.getProperty("gameVersion") ?: "0.0.0-dev"
val configuredApplicationId = providers.gradleProperty("applicationId").orNull
    ?: buildProperties?.getProperty("applicationId") ?: "com.qishimeng.template"
val configuredApplicationName = providers.gradleProperty("appName").orNull
    ?: buildProperties?.getProperty("appName") ?: "启示梦游戏"
val configuredOrientation = buildProperties?.getProperty("orientation") ?: "landscape"
val signingProperties = System.getenv("QSM_SIGNING_PROPERTIES")?.let { propertiesPath ->
    Properties().apply {
        file(propertiesPath).reader(Charsets.UTF_8).use { load(it) }
    }
}

android {
    namespace = "com.qishimeng.game"
    compileSdk = 36

    defaultConfig {
        applicationId = configuredApplicationId
        manifestPlaceholders["gameOrientation"] = configuredOrientation
        resValue("string", "app_name", configuredApplicationName)
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = gameVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("int", "GAME_ID", gameId.toString())
        buildConfigField("String", "GAME_VERSION", "\"${gameVersion}\"")
        buildConfigField("String", "GAME_DEVICE_TYPE", "\"android\"")
        buildConfigField("String", "APPLICATION_ID_VALUE", "\"${configuredApplicationId}\"")
    }

    signingConfigs {
        if (signingProperties != null) {
            create("guiRelease") {
                storeFile = file(signingProperties.getProperty("storeFile"))
                keyAlias = signingProperties.getProperty("keyAlias")
                storePassword = signingProperties.getProperty("storePassword")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingProperties != null) {
                signingConfig = signingConfigs.getByName("guiRelease")
            }
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
        buildConfig = true
    }
}

dependencies {
    implementation(files("libs/auth_number_product-2.14.24-log-online-standard-cuum-release.aar"))
    implementation(files("libs/main-2.2.3-release.aar", "libs/logger-2.2.2-release.aar"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    implementation(libs.gson)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.espresso.core)
}

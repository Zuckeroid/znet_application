import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localSigningProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}

fun configValue(name: String): String? {
    return providers.environmentVariable(name).orNull
        ?: providers.gradleProperty(name).orNull
        ?: localSigningProperties.getProperty(name)
}

fun configValue(name: String, defaultValue: String): String {
    return configValue(name)?.takeIf { it.isNotBlank() } ?: defaultValue
}

fun escapedBuildString(value: String): String {
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
}

fun buildStringField(value: String): String = "\"${escapedBuildString(value)}\""

val appVersionCode = configValue("ZNET_VERSION_CODE")
    ?.toIntOrNull()
    ?: 1
val appVersionName = configValue("ZNET_VERSION_NAME", "0.1.0")
val defaultAuthApiUrl = configValue("ZNET_AUTH_API_URL", "https://my-storage.org")
val defaultAuthApiUrls = configValue("ZNET_AUTH_API_URLS", defaultAuthApiUrl)
val debugAuthApiUrl = configValue("ZNET_DEBUG_AUTH_API_URL", defaultAuthApiUrl)
val debugAuthApiUrls = configValue("ZNET_DEBUG_AUTH_API_URLS", defaultAuthApiUrls)
val releaseAuthApiUrl = configValue("ZNET_RELEASE_AUTH_API_URL", defaultAuthApiUrl)
val releaseAuthApiUrls = configValue("ZNET_RELEASE_AUTH_API_URLS", defaultAuthApiUrls)
val debugApplicationIdSuffix = configValue("ZNET_DEBUG_APPLICATION_ID_SUFFIX")
    ?.trim()
    .orEmpty()

val releaseStoreFilePath = configValue("ZNET_RELEASE_STORE_FILE")
val releaseStorePassword = configValue("ZNET_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = configValue("ZNET_RELEASE_KEY_ALIAS")
val releaseKeyPassword = configValue("ZNET_RELEASE_KEY_PASSWORD") ?: releaseStorePassword
val releaseSigningReady = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { value -> !value.isNullOrBlank() }

android {
    namespace = "com.znet.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.znet.app"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            if (debugApplicationIdSuffix.isNotBlank()) {
                applicationIdSuffix = debugApplicationIdSuffix
            }
            versionNameSuffix = "-debug"
            buildConfigField("String", "APP_ENVIRONMENT", buildStringField("debug"))
            buildConfigField("String", "AUTH_API_URL", buildStringField(debugAuthApiUrl))
            buildConfigField("String", "AUTH_API_URLS", buildStringField(debugAuthApiUrls))
            buildConfigField("boolean", "ENABLE_VERBOSE_LOGS", "true")
        }
        release {
            isMinifyEnabled = true
            signingConfigs.findByName("release")?.let { signingConfig = it }
            buildConfigField("String", "APP_ENVIRONMENT", buildStringField("release"))
            buildConfigField("String", "AUTH_API_URL", buildStringField(releaseAuthApiUrl))
            buildConfigField("String", "AUTH_API_URLS", buildStringField(releaseAuthApiUrls))
            buildConfigField("boolean", "ENABLE_VERBOSE_LOGS", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")

    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

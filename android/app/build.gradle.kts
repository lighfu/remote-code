import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val releaseSigningProperties = Properties().apply {
    val source = rootProject.file("signing.properties")
    if (source.isFile) source.inputStream().use { load(it) }
}
fun releaseSigningValue(environmentName: String, propertyName: String): String? =
    providers.environmentVariable(environmentName).orNull?.takeIf { it.isNotBlank() }
        ?: releaseSigningProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val releaseStoreFile = releaseSigningValue("ANDROID_RELEASE_KEYSTORE_FILE", "storeFile")?.let { rootProject.file(it) }
val releaseStorePassword = releaseSigningValue("ANDROID_RELEASE_STORE_PASSWORD", "storePassword")
val releaseKeyAlias = releaseSigningValue("ANDROID_RELEASE_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = releaseSigningValue("ANDROID_RELEASE_KEY_PASSWORD", "keyPassword")

android {
    namespace = "dev.remotecode"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "net.ajisaiflow.remotecode"
        minSdk = 29
        targetSdk = 36
        // 更新時はバージョン番号とビルド番号を両方上げる。
        versionCode = 70
        versionName = "0.18.11"
    }

    signingConfigs {
        create("release") {
            storeFile = releaseStoreFile
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
            storeType = "PKCS12"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    group = "verification"
    val missing = listOfNotNull(
        "storeFile".takeIf { releaseStoreFile == null },
        "storePassword".takeIf { releaseStorePassword == null },
        "keyAlias".takeIf { releaseKeyAlias == null },
        "keyPassword".takeIf { releaseKeyPassword == null },
    )
    val keystoreFile = releaseStoreFile
    doLast {
        check(missing.isEmpty()) {
            "Release signing is required. Set ${missing.joinToString()} in android/signing.properties or the ANDROID_RELEASE_* environment variables."
        }
        check(keystoreFile?.isFile == true) { "Release keystore was not found. Check storeFile or ANDROID_RELEASE_KEYSTORE_FILE." }
    }
}
tasks.matching { it.name == "preReleaseBuild" || it.name == "validateSigningRelease" }.configureEach {
    dependsOn(validateReleaseSigning)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.google.fonts)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.markdown.m3)
    implementation(libs.latex.renderer)
    debugImplementation(libs.compose.ui.tooling)
}

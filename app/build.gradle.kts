import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val appVersionCode = providers.gradleProperty("app.versionCode").orNull?.toInt()
    ?: error("Missing app.versionCode")
val appVersionName = providers.gradleProperty("app.versionName").orNull
    ?: error("Missing app.versionName")

val sharingStateSchemaVersion = providers.gradleProperty("sharing.stateSchemaVersion").orNull?.toInt()
    ?: error("Missing sharing.stateSchemaVersion")
val sharingPayloadVersion = providers.gradleProperty("sharing.payloadVersion").orNull?.toInt()
    ?: error("Missing sharing.payloadVersion")
val sharingContactCardPrefix = providers.gradleProperty("sharing.contactCardPrefix").orNull
    ?: error("Missing sharing.contactCardPrefix")
val sharingContactCardVersion = providers.gradleProperty("sharing.contactCardVersion").orNull?.toInt()
    ?: error("Missing sharing.contactCardVersion")
val sharingIdentityBundleVersion = providers.gradleProperty("sharing.identityBundleVersion").orNull
    ?: error("Missing sharing.identityBundleVersion")
val sharingCanonicalAclVersion = providers.gradleProperty("sharing.canonicalAclVersion").orNull
    ?: error("Missing sharing.canonicalAclVersion")
val sharingCanonicalPushVersion = providers.gradleProperty("sharing.canonicalPushVersion").orNull
    ?: error("Missing sharing.canonicalPushVersion")
val sharingCanonicalPullVersion = providers.gradleProperty("sharing.canonicalPullVersion").orNull
    ?: error("Missing sharing.canonicalPullVersion")
val sharingCanonicalSelfStatusVersion = providers.gradleProperty("sharing.canonicalSelfStatusVersion").orNull
    ?: error("Missing sharing.canonicalSelfStatusVersion")
val sharingCanonicalClearVersion = providers.gradleProperty("sharing.canonicalClearVersion").orNull
    ?: error("Missing sharing.canonicalClearVersion")
val sharingCanonicalContactCardVersion = providers.gradleProperty("sharing.canonicalContactCardVersion").orNull
    ?: error("Missing sharing.canonicalContactCardVersion")
val sharingPushContextVersion = providers.gradleProperty("sharing.pushContextVersion").orNull
    ?: error("Missing sharing.pushContextVersion")
val sharingRelayApiVersion = providers.gradleProperty("sharing.relayApiVersion").orNull
    ?: error("Missing sharing.relayApiVersion")
val sharingSecureStorageBlobVersion = providers.gradleProperty("sharing.secureStorageBlobVersion").orNull?.toInt()
    ?: error("Missing sharing.secureStorageBlobVersion")
val locationDbLocalStateVersion = providers.gradleProperty("locationDb.localStateVersion").orNull?.toInt()
    ?: error("Missing locationDb.localStateVersion")
val locationDbExportPayloadVersion = providers.gradleProperty("locationDb.exportPayloadVersion").orNull?.toInt()
    ?: error("Missing locationDb.exportPayloadVersion")
val locationDbBundleVersion = providers.gradleProperty("locationDb.bundleVersion").orNull
    ?: error("Missing locationDb.bundleVersion")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.blackbox"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.blackbox"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("int", "SHARING_STATE_SCHEMA_VERSION", sharingStateSchemaVersion.toString())
        buildConfigField("int", "SHARING_PAYLOAD_VERSION", sharingPayloadVersion.toString())
        buildConfigField("String", "SHARING_CONTACT_CARD_PREFIX", "\"$sharingContactCardPrefix\"")
        buildConfigField("int", "SHARING_CONTACT_CARD_VERSION", sharingContactCardVersion.toString())
        buildConfigField("String", "SHARING_IDENTITY_BUNDLE_VERSION", "\"$sharingIdentityBundleVersion\"")
        buildConfigField("String", "SHARING_CANONICAL_ACL_VERSION", "\"$sharingCanonicalAclVersion\"")
        buildConfigField("String", "SHARING_CANONICAL_PUSH_VERSION", "\"$sharingCanonicalPushVersion\"")
        buildConfigField("String", "SHARING_CANONICAL_PULL_VERSION", "\"$sharingCanonicalPullVersion\"")
        buildConfigField(
            "String",
            "SHARING_CANONICAL_SELF_STATUS_VERSION",
            "\"$sharingCanonicalSelfStatusVersion\""
        )
        buildConfigField("String", "SHARING_CANONICAL_CLEAR_VERSION", "\"$sharingCanonicalClearVersion\"")
        buildConfigField(
            "String",
            "SHARING_CANONICAL_CONTACT_CARD_VERSION",
            "\"$sharingCanonicalContactCardVersion\""
        )
        buildConfigField("String", "SHARING_PUSH_CONTEXT_VERSION", "\"$sharingPushContextVersion\"")
        buildConfigField("String", "SHARING_RELAY_API_VERSION", "\"$sharingRelayApiVersion\"")
        buildConfigField("int", "SHARING_SECURE_STORAGE_BLOB_VERSION", sharingSecureStorageBlobVersion.toString())
        buildConfigField("int", "LOCATION_DB_LOCAL_STATE_VERSION", locationDbLocalStateVersion.toString())
        buildConfigField("int", "LOCATION_DB_EXPORT_PAYLOAD_VERSION", locationDbExportPayloadVersion.toString())
        buildConfigField("String", "LOCATION_DB_BUNDLE_VERSION", "\"$locationDbBundleVersion\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.expandProjection", "true")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.zetetic.sqlcipher)
    implementation(libs.androidx.documentfile)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tink.android)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.maplibre.android.sdk)
    implementation(libs.google.material)
    ksp(libs.androidx.room.compiler)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

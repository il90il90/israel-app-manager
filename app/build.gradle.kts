import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val versionProps = Properties().apply { rootProject.file("version.properties").inputStream().use(::load) }

// The release key lives outside this repository: either a checkout of the private keys
// repository (-Piam.keystoreDir=... or IAM_KEYSTORE_DIR) or CI secrets (IAM_KEYSTORE_FILE +
// IAM_KEYSTORE_PASSWORD). Without one, release builds are signed with the debug key and
// must never be published; the publish workflows check the certificate.
val keystoreDir: String? = (findProperty("iam.keystoreDir") as String?) ?: System.getenv("IAM_KEYSTORE_DIR")
val keystoreFileEnv: String? = System.getenv("IAM_KEYSTORE_FILE")
val keystoreProps = Properties().apply {
    val f = keystoreDir?.let { file("$it/keystore.properties") }
    if (f != null && f.exists()) f.inputStream().use(::load)
}
val hasReleaseKey = keystoreProps.containsKey("storePassword") ||
    (!keystoreFileEnv.isNullOrBlank() && !System.getenv("IAM_KEYSTORE_PASSWORD").isNullOrBlank())

android {
    namespace = "com.il90.appmanager"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.il90.appmanager"
        minSdk = 26
        targetSdk = 36
        versionCode = versionProps.getProperty("VERSION_CODE").toInt()
        versionName = versionProps.getProperty("VERSION_NAME")
        buildConfigField("String", "CATALOG_REPO", "\"il90il90/israel-app-manager\"")
        // -Piam.catalogBranch=... points a test build at another branch of the catalog.
        buildConfigField("String", "CATALOG_BRANCH", "\"${(findProperty("iam.catalogBranch") as String?) ?: "catalog"}\"")
        vectorDrawables { useSupportLibrary = true }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                if (keystoreProps.containsKey("storePassword")) {
                    storeFile = file("$keystoreDir/${keystoreProps.getProperty("storeFile", "release.jks")}")
                    storePassword = keystoreProps.getProperty("storePassword")
                    keyAlias = keystoreProps.getProperty("keyAlias", "iam")
                    keyPassword = keystoreProps.getProperty("keyPassword", keystoreProps.getProperty("storePassword"))
                } else {
                    storeFile = file(keystoreFileEnv!!)
                    storePassword = System.getenv("IAM_KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("IAM_KEY_ALIAS") ?: "iam"
                    keyPassword = System.getenv("IAM_KEY_PASSWORD") ?: System.getenv("IAM_KEYSTORE_PASSWORD")
                }
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    androidResources { localeFilters += listOf("en", "iw") }
    lint {
        abortOnError = true
        checkReleaseBuilds = false
        disable += listOf("MissingTranslation", "QueryAllPackagesPermission", "GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "ObsoleteSdkInt")
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.6")
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("androidx.datastore:datastore-preferences:1.2.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")

    testImplementation("junit:junit:4.13.2")
}

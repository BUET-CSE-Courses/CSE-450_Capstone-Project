import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinCompose)
}

// Machine-specific values live in local.properties, which is gitignored
// (Capstone_Android/.gitignore). Keys this module reads, one pair per web end:
//   webend.local.clientId     AZURE_CLIENT_ID of YOUR local web end (your own registration)
//   webend.deployed.clientId  AZURE_CLIENT_ID of the deployed web end (the teammate's registration)
//   webend.deployedUrl        deployed web end, e.g. https://<app>.azurecontainerapps.io
//                             (with or without the trailing /api)
//   webend.signatureHash      base64 SHA-1 of the signing cert, NOT url-encoded. Shared: both
//                             registrations need the same Android redirect for this package.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun localProp(key: String): String = localProps.getProperty(key)?.trim().orEmpty()

// Values go into Java string literals and a manifest attribute; refuse the
// characters that would break either rather than escaping them.
fun checked(key: String): String = localProp(key).also {
    require(it.none { c -> c == '"' || c == '\\' || c == '<' || c == '&' }) {
        "local.properties $key contains a quote, backslash, < or &"
    }
}

/** The server origin or its /api URL, normalised to ".../api/". Blank stays blank. */
fun apiBaseUrl(raw: String): String {
    if (raw.isBlank()) return ""
    val trimmed = raw.trimEnd('/')
    return if (trimmed.endsWith("/api")) "$trimmed/" else "$trimmed/api/"
}

val localClientId = checked("webend.local.clientId")
val deployedClientId = checked("webend.deployed.clientId")
val msalSignatureHash = checked("webend.signatureHash")
val deployedBaseUrl = apiBaseUrl(checked("webend.deployedUrl"))

android {
    namespace = "com.example.capstone"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.capstone"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Microsoft sign-in. The redirect URI is msauth://<applicationId>/<url-encoded hash>;
        // the manifest's BrowserTabActivity filter takes the raw hash as its path. The
        // client id is per flavor (below); the redirect is the same for both.
        buildConfigField("String", "MSAL_SIGNATURE_HASH", "\"$msalSignatureHash\"")
        manifestPlaceholders["msalSignatureHash"] = msalSignatureHash.ifBlank { "signature-hash-not-set" }
    }

    // Which web end the app talks to, and the Entra registration that web end
    // validates tokens against: BASE_URL and MSAL_CLIENT_ID always come as a pair.
    // Both flavors keep the same applicationId, so the Android redirect is the
    // same string in both registrations.
    flavorDimensions += "server"
    productFlavors {
        create("local") {
            dimension = "server"
            isDefault = true
            // Your own web end over USB: adb reverse tcp:8000 tcp:8000
            // That mapping is NOT persistent - re-run it after every replug,
            // or the app will fail to connect with no obvious cause.
            buildConfigField("String", "BASE_URL", "\"http://localhost:8000/api/\"")
            buildConfigField("String", "MSAL_CLIENT_ID", "\"$localClientId\"")
            // Server re-marking of low-confidence boxes is off locally: a local web end
            // usually has no SELF_HOSTED_LLM_URL. Debug builds can flip it on the courses screen.
            buildConfigField("boolean", "FALLBACK_ENABLED", "false")
        }
        create("deployed") {
            dimension = "server"
            // local.properties webend.deployedUrl / webend.deployed.clientId. Blank
            // builds, but the app refuses to sign in and says which key is missing.
            buildConfigField("String", "BASE_URL", "\"$deployedBaseUrl\"")
            buildConfigField("String", "MSAL_CLIENT_ID", "\"$deployedClientId\"")
            buildConfigField("boolean", "FALLBACK_ENABLED", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        // Required for the BASE_URL and MSAL buildConfigFields above.
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Robolectric needs the merged resources/manifest.
            isIncludeAndroidResources = true
            // Lets plain JVM tests call android.util.Log without an exception.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Page registration and answer-box cropping. Owns Layout, AnswerCrop and the
    // marker contract types; the app adapts the server response onto them and
    // computes no geometry of its own.
    implementation(project(":extractor"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Microsoft sign-in (Entra ID)
    implementation(libs.msal)

    // Retrofit
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Coil
    implementation(libs.coil.compose)

    // LiteRT-LM (on-device LLM grading)
    implementation(libs.litertlm.android)

    // ExifInterface
    implementation(libs.androidx.exifinterface)

    // Grading run: WorkManager (expedited, foreground) and its saved progress (DataStore)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    // Real org.json: MsalConfig builds its JSON with it, and android.jar's copy is a stub.
    testImplementation(libs.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

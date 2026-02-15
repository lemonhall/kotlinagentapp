plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.lsl.kotlin_agent_app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lsl.kotlin_agent_app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        val dotenv: Map<String, String> =
            run {
                val envFile = rootProject.file(".env")
                if (!envFile.exists()) return@run emptyMap()

                val out = linkedMapOf<String, String>()
                envFile.readLines(Charsets.UTF_8).forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.isBlank() || line.startsWith("#")) return@forEach
                    val idx = line.indexOf('=')
                    if (idx <= 0) return@forEach
                    val key = line.substring(0, idx).trim()
                    var value = line.substring(idx + 1).trim()
                    if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
                        value = value.substring(1, value.length - 1)
                    }
                    out[key] = value
                }
                out
            }

        fun escapedForBuildConfig(value: String): String {
            return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
        }

        release {
            isMinifyEnabled = false
            buildConfigField("String", "DEFAULT_OPENAI_BASE_URL", "\"\"")
            buildConfigField("String", "DEFAULT_OPENAI_API_KEY", "\"\"")
            buildConfigField("String", "DEFAULT_MODEL", "\"\"")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            val baseUrl = dotenv["OPENAI_BASE_URL"].orEmpty()
            val apiKey = dotenv["OPENAI_API_KEY"].orEmpty()
            val model = dotenv["MODEL"].orEmpty()
            buildConfigField("String", "DEFAULT_OPENAI_BASE_URL", "\"${escapedForBuildConfig(baseUrl)}\"")
            buildConfigField("String", "DEFAULT_OPENAI_API_KEY", "\"${escapedForBuildConfig(apiKey)}\"")
            buildConfigField("String", "DEFAULT_MODEL", "\"${escapedForBuildConfig(model)}\"")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {

    implementation("me.lemonhall.openagentic:openagentic-sdk-kotlin:0.1.0-SNAPSHOT")

    coreLibraryDesugaring(libs.android.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation("com.squareup.okio:okio:3.8.0")

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

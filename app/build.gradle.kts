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
            buildConfigField("String", "DEFAULT_TAVILY_URL", "\"\"")
            buildConfigField("String", "DEFAULT_TAVILY_API_KEY", "\"\"")
            buildConfigField("String", "DEFAULT_HTTP_PROXY", "\"\"")
            buildConfigField("String", "DEFAULT_HTTPS_PROXY", "\"\"")
            buildConfigField("boolean", "DEFAULT_PROXY_ENABLED", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            val baseUrl = dotenv["OPENAI_BASE_URL"].orEmpty()
            val apiKey = dotenv["OPENAI_API_KEY"].orEmpty()
            val model = dotenv["MODEL"].orEmpty()
            val tavilyUrl = dotenv["TAVILY_URL"].orEmpty()
            val tavilyApiKey = dotenv["TAVILY_API_KEY"].orEmpty()
            val httpProxy = dotenv["HTTP_PROXY"].orEmpty()
            val httpsProxy = dotenv["HTTPS_PROXY"].orEmpty()
            buildConfigField("String", "DEFAULT_OPENAI_BASE_URL", "\"${escapedForBuildConfig(baseUrl)}\"")
            buildConfigField("String", "DEFAULT_OPENAI_API_KEY", "\"${escapedForBuildConfig(apiKey)}\"")
            buildConfigField("String", "DEFAULT_MODEL", "\"${escapedForBuildConfig(model)}\"")
            buildConfigField("String", "DEFAULT_TAVILY_URL", "\"${escapedForBuildConfig(tavilyUrl)}\"")
            buildConfigField("String", "DEFAULT_TAVILY_API_KEY", "\"${escapedForBuildConfig(tavilyApiKey)}\"")
            buildConfigField("String", "DEFAULT_HTTP_PROXY", "\"${escapedForBuildConfig(httpProxy)}\"")
            buildConfigField("String", "DEFAULT_HTTPS_PROXY", "\"${escapedForBuildConfig(httpsProxy)}\"")
            buildConfigField("boolean", "DEFAULT_PROXY_ENABLED", (httpProxy.isNotBlank() || httpsProxy.isNotBlank()).toString())
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
    implementation("com.lsl:agent-browser-kotlin:0.1.0")

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
    implementation(libs.jgit)
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.10")
    implementation("com.squareup.okio:okio:3.8.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")

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

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kotlin-agent-app"
include(":app")

// Local SDK (composite build). See docs/plan/v2-sdk-composite-build.md
includeBuild("external/openagentic-sdk-kotlin")

// Local agent-browser-kotlin (composite build).
includeBuild("external/agent-browser-kotlin")

// Local jediterm-android (terminal emulator + compose view). Use dependency substitution since the included
// build does not publish stable group/version coordinates.
includeBuild("external/jediterm-android") {
    dependencySubstitution {
        substitute(module("com.lemonhall.jediterm.android:lib")).using(project(":lib"))
        substitute(module("com.lemonhall.jediterm:tinyemu")).using(project(":tinyemu"))
    }
}
 

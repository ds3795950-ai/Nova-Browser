pluginManagement {
    repositories {
        maven("https://maven.cefrium.com") { content { includeGroup("com.cefrium") } }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.cefrium.com") { content { includeGroup("com.cefrium") } }
        maven(url = "https://maven.mozilla.org/maven2/")
    }
}

rootProject.name = "NovaBrowser"
include(":app")

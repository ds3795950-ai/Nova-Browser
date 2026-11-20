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
        maven(url = "https://maven.mozilla.org/maven2/")
        maven("https://maven.cefrium.com") { content { includeGroup("com.cefrium") } }
    }
}

rootProject.name = "NovaBrowser"
include(":app")

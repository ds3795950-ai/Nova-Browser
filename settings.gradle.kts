pluginManagement {
    repositories {
        maven("https://maven.cefrium.com") { content { includeGroup("com.cefrium") } }
        maven(url = "https://maven-central.storage-download.googleapis.com/maven2/")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven(url = "https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        maven(url = "https://maven.mozilla.org/maven2/")
        maven("https://maven.cefrium.com") { content { includeGroup("com.cefrium") } }
    }
}

rootProject.name = "NovaBrowser"
include(":app")

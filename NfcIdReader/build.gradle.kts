pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://artifactory.appodeal.com/appodeal-public/")
            content { includeGroup("com.gemalto.jp2") }
        }
    }
}
rootProject.name = "NfcIdReader"
include(":app")

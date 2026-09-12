pluginManagement {
    repositories {
        // Optional build-environment override; separate multiple mirrors with commas.
        val configuredMirrors = System.getenv("KTV_ANDROID_REPO_MIRROR")
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()
        configuredMirrors.forEach(::maven)
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val configuredMirrors = System.getenv("KTV_ANDROID_REPO_MIRROR")
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()
        configuredMirrors.forEach(::maven)
        google()
        mavenCentral()
    }
}

rootProject.name = "家享K歌"
include(":app")

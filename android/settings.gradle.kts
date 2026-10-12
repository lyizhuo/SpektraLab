// android/settings.gradle.kts -- the SpektraLab Android build.
//
// Two modules: `:engine` (libspektralab.so + the engine assets) and `:app`
// (the Compose UI). The engine module consumes the repository's own engine
// through CMake add_subdirectory(), so this project has no copy of the
// engine in it -- see android/README.md for the upstream-sync rules.
pluginManagement {
    repositories {
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
    }
}

rootProject.name = "spektralab-android"
include(":app")
include(":engine")

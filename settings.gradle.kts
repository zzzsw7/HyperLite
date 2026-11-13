pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://api.xposed.info/")
        maven("https://maven.aliyun.com/repository/public")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://api.xposed.info/")
        maven("https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "HyperLite"
include(":app")

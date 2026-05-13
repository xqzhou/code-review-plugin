import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    id("org.jetbrains.intellij.platform") version "2.14.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    val localPlatformPath = providers.gradleProperty("localPlatformPath")

    intellijPlatform {
        if (localPlatformPath.isPresent) {
            local(localPlatformPath)
        } else {
            goland(providers.gradleProperty("platformVersion"))
        }
        bundledPlugin("org.jetbrains.plugins.go")
        bundledPlugin("Git4Idea")
        bundledModule("intellij.platform.vcs.dvcs.impl")
        testFramework(TestFrameworkType.Platform)
    }
    implementation("com.google.code.gson:gson:2.11.0")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = "253"
            untilBuild = "253.*"
        }
    }

    publishing {
        // token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks {
    wrapper {
        gradleVersion = "9.0.0"
    }
}

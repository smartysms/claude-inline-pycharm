import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.claudeinline"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val platformPath = providers.gradleProperty("platformPath")
        if (platformPath.isPresent) {
            local(platformPath.get())
        } else {
            // Fallback when no local IDE is configured: download PyCharm from JetBrains.
            pycharm(providers.gradleProperty("platformVersion").get())
        }
        bundledPlugin("PythonCore")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

// The 262 platform jars are Java 25 class files (major version 69), so the plugin must target 25 as well.
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            // Explicit null: without it the plugin would default to "262.*".
            untilBuild = provider { null }
        }
    }
    // Only needed for the Settings search index; slow and pointless for a single settings page.
    buildSearchableOptions = false
}

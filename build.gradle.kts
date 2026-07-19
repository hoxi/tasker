plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "net.tagpad"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // IntelliJ IDEA 2026.1 (bundles JBR 25). The separate Community (IC) artifact
        // is no longer published since 2025.3 — the unified intellijIdea(...) is used instead.
        intellijIdea("2026.1")
        // Task Management plugin: TaskManager / TaskRepository / Task live here.
        bundledPlugin("com.intellij.tasks")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // 2026.1 == build 261.x
            sinceBuild = "261"
        }
    }
}

kotlin {
    jvmToolchain(25)
}

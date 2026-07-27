
fun properties(key: String) = project.findProperty(key).toString()

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.18.1"
    id("org.jetbrains.changelog") version "2.5.0"
}

group = properties("pluginGroup")
version = properties("pluginVersion")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
        // Without this, a Kotlin class implementing a Kotlin interface gets a compatibility bridge for
        // every default member it inherits. ToolWindowFactory is such an interface, so our factory was
        // emitting overrides of isApplicable and isDoNotActivateOnStart — both deprecated — that the
        // source never mentions, and the plugin verifier reported them as ours. Real JVM default
        // methods leave the inherited members where they belong.
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}

fun getMajorVersion(version: String): String {
    val parts = version.split(".")
    return if (parts.size >= 2) "${parts[0]}.${parts[1]}" else version
}

changelog {
    version.set(properties("pluginVersion"))
    groups.set(emptyList())
    // Configure to accept your version format (YYYY.N or YYYY.N.N)
    headerParserRegex.set("""(\d{4}\.\d+(?:\.\d+)?)""".toRegex())
    keepUnreleasedSection.set(false)
}

dependencies {
    intellijPlatform {
        val version: String = providers.gradleProperty("platformVersion").get()

        // Deliberately the unified IDEA artifact rather than create("IU", version) as the CLion plugin
        // does. Since 2025.3 the Community distribution is no longer published separately, and the
        // unified type shares its "IU" code with the legacy Ultimate one while resolving a different
        // artifact — "idea" against "ideaIU". Selected by string, "IU" therefore lands on the legacy
        // artifact, which does not carry the bundled Task Management plugin this plugin depends on.
        intellijIdea(version) {
            useInstaller = !version.endsWith("EAP-SNAPSHOT")
        }
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',').filter { s -> s.isNotBlank() } })

        // Marketplace plugins, as "id:version". Task Management is bundled up to 2026.1 and a separate
        // download from 2026.2, so which of the two lists it belongs in depends on platformVersion —
        // see gradle.properties. Declaring it here also installs it into the runIde sandbox, so the
        // plugin can actually be exercised on a platform that no longer ships it.
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',').filter { s -> s.isNotBlank() } })

        // Same, but by id alone: the newest version the target platform accepts is looked up instead of
        // being pinned here, which is what we want for a plugin that is versioned per IDE build.
        compatiblePlugins(providers.gradleProperty("platformCompatiblePlugins").map { it.split(',').filter { s -> s.isNotBlank() } })

        // Platform v2 modules. The Task Management download carries only the tracker implementations —
        // the API it is written against, com.intellij.tasks.Task and TaskRepository, stays in the
        // platform as intellij.platform.tasks and has to be asked for by name once the plugin is no
        // longer bundled.
        bundledModules(providers.gradleProperty("platformBundledModules").map { it.split(',').filter { s -> s.isNotBlank() } })
    }
}

intellijPlatform {
    signing {
        certificateChainFile = providers.environmentVariable("CERTIFICATE_CHAIN_FILE")
            .map { File(it) }
        privateKeyFile = providers.environmentVariable("PRIVATE_KEY_FILE")
            .map { File(it) }
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }

    pluginConfiguration {
        changeNotes.set(provider {
            val majorVersion = getMajorVersion(project.version.toString())

            // Get all changelog entries that start with the major version
            val matchingEntries = changelog.getAll().values
                .filter { it.version.startsWith(majorVersion) }
            if (matchingEntries.isNotEmpty()) {
                matchingEntries.joinToString("\n\n") {
                    changelog.renderItem(it, org.jetbrains.changelog.Changelog.OutputType.HTML)
                }
            } else {
                // Fallback to current version only
                changelog.renderItem(
                    changelog.getOrNull(properties("pluginVersion"))
                        ?: changelog.getLatest(),
                    org.jetbrains.changelog.Changelog.OutputType.HTML
                )
            }
        })
        ideaVersion {
            sinceBuild = properties("pluginSinceBuild")
            untilBuild = provider { null }
        }

        description.set(provider {
            val readme = file("README.md").readText()

            // Capture the content of "## Overview" up to the next "##" or end of file
            val match = Regex(
                pattern = "(?s)^##\\s*Overview\\s*(.*?)(?=^##\\s|\\z)",
                options = setOf(RegexOption.MULTILINE)
            ).find(readme) ?: throw GradleException(
                "README.md must contain a '## Overview' section."
            )

            val md = match.groupValues[1].trim()
            var html = org.jetbrains.changelog.markdownToHTML(md)
            val sanitized = html
                .replaceFirst(Regex("^\\s*<p>"), "")
                .replaceFirst(Regex("</p>\\s*"), "")
            sanitized
        })
    }
}

gradle.taskGraph.whenReady {
    val isRelease = hasTask(":signPlugin") || hasTask(":publishPlugin") || hasTask(":verifyPlugin")
    tasks.named("buildSearchableOptions") { enabled = isRelease }
    tasks.named("prepareJarSearchableOptions") { enabled = isRelease }
    tasks.named("jarSearchableOptions") { enabled = isRelease }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
        distributionType = Wrapper.DistributionType.BIN
    }

    register<DefaultTask>("verifyWrapperVersion") {
        // Wire expected version as a declared input so the action doesn't capture Project
        val expectedVersion = providers.gradleProperty("gradleVersion").orElse("")
        inputs.property("expectedGradleVersion", expectedVersion)

        doLast {
            val expected = inputs.properties["expectedGradleVersion"] as String
            if (expected.isBlank()) return@doLast

            val actual = GradleVersion.current().version
            if (expected != actual) {
                throw GradleException(
                    "Gradle Wrapper is $actual but expected is gradleVersion=$expected. " +
                            "Run: ./gradlew wrapper --gradle-version $expected"
                )
            }
        }
    }
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "25"
        targetCompatibility = "25"
    }
}

// Verify that we have the expected version of the Gradle wrapper
listOf("build", "buildPlugin").forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        dependsOn(tasks.named("verifyWrapperVersion"))
    }
}

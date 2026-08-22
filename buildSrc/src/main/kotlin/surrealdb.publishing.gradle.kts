import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar

fun prop(name: String): String =
    project.findProperty(name)?.toString()
        ?: error("${project.path} is missing the '$name' property (module gradle.properties)")

val artifactBase = prop("POM_ARTIFACT_ID")
val compatibilityArtifactBase = project.findProperty("POM_COMPAT_ARTIFACT_ID")?.toString()
val pomName = prop("POM_NAME")
val pomDescription = prop("POM_DESCRIPTION")

val javadocJar =
    tasks.register<Jar>("javadocJar") {
        group = "documentation"
        description = "Packages the Dokka HTML output as the publishable -javadoc.jar."
        archiveClassifier.set("javadoc")
        from(tasks.named("dokkaGeneratePublicationHtml"))
    }

afterEvaluate {
    extensions.configure<PublishingExtension> {
        fun MavenPublication.configurePublication(base: String) {
            artifact(javadocJar)
            artifactId =
                when (name) {
                    "kotlinMultiplatform", "queryMultiplatform" -> base
                    else -> "$base-$name"
                }

            pom {
                this.name.set(pomName)
                this.description.set(pomDescription)
                url.set(prop("POM_URL"))

                licenses {
                    license {
                        this.name.set(prop("POM_LICENSE_NAME"))
                        url.set(prop("POM_LICENSE_URL"))
                    }
                }

                developers {
                    developer {
                        id.set(prop("POM_DEVELOPER_ID"))
                        this.name.set(prop("POM_DEVELOPER_NAME"))
                    }
                }

                scm {
                    url.set(prop("POM_SCM_URL"))
                    connection.set(prop("POM_SCM_CONNECTION"))
                    developerConnection.set(prop("POM_SCM_DEV_CONNECTION"))
                }
            }
        }

        val primaryPublications = publications.withType<MavenPublication>().toList()
        primaryPublications.forEach {
            it.configurePublication(compatibilityArtifactBase ?: artifactBase)
        }

        compatibilityArtifactBase?.let {
            publications.create<MavenPublication>("queryMultiplatform") {
                from(project.components.getByName("kotlin"))
                configurePublication(artifactBase)
            }
        }

        val onApple = System.getProperty("os.name").startsWith("Mac")
        val standardSuffixes = listOf("", "-jvm", "-android")
        val appleSuffixes = listOf("-iosX64", "-iosArm64", "-iosSimulatorArm64")
        val publishedBase = compatibilityArtifactBase ?: artifactBase
        val expected =
            buildSet {
                add(artifactBase)
                (standardSuffixes + if (onApple) appleSuffixes else emptyList()).forEach {
                    add("$publishedBase$it")
                }
            }.toSortedSet()
        val skipped =
            if (onApple) {
                emptySet()
            } else {
                appleSuffixes.map { "$publishedBase$it" }.toSortedSet()
            }
        val mavenPublications = publications.withType<MavenPublication>().toList()
        val actual = mavenPublications.map { it.artifactId }.toSortedSet()
        val withJavadoc =
            provider {
                mavenPublications
                    .filter { publication ->
                        publication.artifacts.any { it.classifier == "javadoc" }
                    }.map { it.artifactId }
                    .toSortedSet()
            }

        tasks.register("verifyPublicationJavadoc") {
            group = "verification"
            description = "Fails if a publication would be released without a -javadoc.jar."
            doLast {
                val missing = expected - withJavadoc.get()
                check(missing.isEmpty()) {
                    "${project.path} would publish ${missing.joinToString()} without a " +
                        "-javadoc.jar. Maven Central rejects a release without one."
                }
            }
        }

        tasks.register("verifyPublicationCoordinates") {
            group = "verification"
            description = "Fails if this module's published Maven coordinates change."
            doLast {
                val missing = expected - actual
                val unexpected = actual - expected - skipped
                check(missing.isEmpty() && unexpected.isEmpty()) {
                    buildString {
                        appendLine("${project.path} publishes different coordinates than expected.")
                        appendLine("  missing:   ${missing.ifEmpty { "-" }}")
                        appendLine("  unexpected: ${unexpected.ifEmpty { "-" }}")
                        appendLine("  not checked on this host: ${skipped.ifEmpty { "-" }}")
                        append("Update the module POM properties only for an intentional coordinate change.")
                    }
                }
            }
        }
    }
}

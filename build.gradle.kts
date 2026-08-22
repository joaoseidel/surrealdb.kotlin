import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotest) apply false
}

val jdkToolchainVersion = 25
val androidCompileSdk = 35
val androidMinSdk = 26
val ktlintVersion =
    libs.versions.ktlint.cli
        .get()

dependencies {
    dokka(project(":surrealdb-kotlin-core"))
    dokka(project(":surrealdb-kotlin-query"))
    dokka(project(":surrealdb-kotlin-spectron"))
}

tasks.register<VerifyCoreQueryBoundary>("verifyCoreQueryBoundary") {
    group = "verification"
    description = "Fails if core declares a dependency on query or imports query-owned API."
    coreBuild.set(layout.projectDirectory.file("surrealdb-kotlin-core/build.gradle.kts"))
    coreSources.from(fileTree("surrealdb-kotlin-core/src") { include("**/*.kt") })
}

tasks.register<VerifyCoreQueryClasspath>("verifyCoreQueryClasspath") {
    group = "verification"
    description = "Fails if the core compile classpath contains a query artifact."
    coreClasspath.from(
        project(":surrealdb-kotlin-core").configurations.named("jvmTestCompileClasspath"),
    )
}

allprojects {
    group = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION_NAME").get()

    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    val generatedRoot =
        layout.buildDirectory
            .get()
            .asFile.path

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(ktlintVersion)

        filter {
            exclude { it.file.path.startsWith(generatedRoot) }
        }
    }
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
        apply(plugin = "org.jetbrains.dokka")
        apply(plugin = "maven-publish")

        configure<KotlinMultiplatformExtension> {
            explicitApi()

            jvmToolchain(jdkToolchainVersion)

            targets.withType<KotlinMultiplatformAndroidLibraryTarget>().configureEach {
                compileSdk = androidCompileSdk
                minSdk = androidMinSdk

                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_11)
                }
            }

            jvm {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_11)
                }
            }

            iosX64()
            iosArm64()
            iosSimulatorArm64()

            sourceSets.getByName("commonTest").dependencies {
                implementation(kotlin("test"))
                implementation(libs.bundles.testing)
            }

            sourceSets.getByName("jvmTest").dependencies {
                implementation(libs.junit.jupiter)
                implementation(libs.kotest.runner.junit5)
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("failed")
                showExceptions = true
                showCauses = true
                showStackTraces = true
                exceptionFormat = TestExceptionFormat.FULL
            }
        }

        apply(plugin = "surrealdb.publishing")
    }
}

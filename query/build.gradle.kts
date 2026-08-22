plugins {
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotest)
}

kotlin {
    android {
        namespace = "com.surrealdb.kotlin.query"

        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
        }

        jvmTest.dependencies {
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.websockets)
        }

        getByName("androidDeviceTest").dependencies {
            implementation(libs.bundles.androidx.test)
        }
    }
}

dependencies {
    dokka(project(":core"))
}

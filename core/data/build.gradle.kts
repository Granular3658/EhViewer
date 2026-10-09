plugins {
    alias(libs.plugins.ehviewer.multiplatform.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(projects.core.common)
                api(libs.androidx.datastore)
                api(libs.androidx.room3.paging)
                implementation(libs.ktor.client.core)
            }
        }
        androidMain {
            dependencies {
                implementation(project.dependencies.platform(libs.okhttp.bom))
                implementation(libs.ktor.client.okhttp)
            }
        }
    }
}

dependencies {
    add("kspAndroid", libs.androidx.room3.compiler)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

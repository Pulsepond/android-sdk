import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = "dev.pulsepond"
version = "0.1.0"

android {
    namespace = "dev.pulsepond.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = false
    }

    androidResources.enable = false

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    testOptions {
        unitTests.all {
            it.maxHeapSize = "1g"
        }
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "dev.pulsepond"
            artifactId = "android-sdk"
            version = project.version.toString()
            afterEvaluate {
                from(components["release"])
            }
            pom {
                name = "Pulsepond Android SDK"
                description = "Privacy-conscious Android event collector for Pulsepond"
                url = "https://pulsepond.dev"
                licenses {
                    license {
                        name = "Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
                scm {
                    connection = "scm:git:https://github.com/Pulsepond/android-sdk.git"
                    developerConnection = "scm:git:ssh://git@github.com/Pulsepond/android-sdk.git"
                    url = "https://github.com/Pulsepond/android-sdk"
                }
                developers {
                    developer {
                        id = "pulsepond"
                        name = "Pulsepond"
                        url = "https://pulsepond.dev"
                    }
                }
            }
        }
    }
}

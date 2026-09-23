plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    // id("com.android.library")
}

kotlin {
    // androidTarget {
    //     compilations.all {
    //         kotlinOptions {
    //             jvmTarget = "1.8"
    //         }
    //     }
    // }
    
    jvm("desktop") {
        compilations.all {
            
        }
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
                implementation("io.ktor:ktor-network:2.3.8")
                implementation("com.squareup.okio:okio:3.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
                
                // Cryptography
                implementation("dev.whyoleg.cryptography:cryptography-core:0.6.0") 
                // But the user specifically asked for 0.6.0. I will use 0.4.0 first just to check compilation, then move to 0.6.0 if it works.
                // Wait, no, user said "Utilise actuellement : 0.6.0". I must use 0.6.0.
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation("org.jmdns:jmdns:3.5.9")
                implementation("dev.whyoleg.cryptography:cryptography-provider-jdk:0.6.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
                implementation("dev.whyoleg.cryptography:cryptography-provider-jdk:0.6.0")
            }
        }
    }
}

/*
android {
    namespace = "com.fastdrop.shared"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }
}
*/

tasks.register("printClasspath") {
    doLast {
        val cp = configurations.getByName("desktopRuntimeClasspath").files.joinToString(":")
        println(cp)
    }
}

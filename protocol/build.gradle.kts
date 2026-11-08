import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nshare.protocol"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    testOptions {
        unitTests.all {
            // LiveReceiverTest is opt-in via these variables; pass them through to the test JVM.
            listOf("NSHARE_TV_HOST", "NSHARE_SCENARIO", "NSHARE_PIN_FILE", "NSHARE_KEY_FILE").forEach { k ->
                System.getenv(k)?.let { v -> it.environment(k, v) }
            }
            it.testLogging.showStandardStreams = true
            it.outputs.upToDateWhen { false }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Generates self-signed test certificates for the JVM TLS handshake tests.
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.79")
}

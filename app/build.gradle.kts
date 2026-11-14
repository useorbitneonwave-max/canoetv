plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.canoebay.canoetv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.canoebay.canoetv"
        minSdk = 21
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.1.$buildNumber"
        // The GitHub repo this app updates itself from
        val repo = System.getenv("GITHUB_REPOSITORY") ?: "useorbitneonwave-max/canoetv"
        buildConfigField("String", "REPO", "\"$repo\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // One fixed signing key, so every new build installs over the old one.
    signingConfigs {
        create("shared") {
            storeFile = file("canoetv.keystore")
            storePassword = "canoetv"
            keyAlias = "canoetv"
            keyPassword = "canoetv"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("io.coil-kt:coil:2.6.0")
}

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.hank.ascentswitch"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.hank.ascentswitch"
        minSdk = 27
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("androidx.preference:preference-ktx:1.2.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    // Hammerhead Extensions SDK
    implementation("io.hammerhead:karoo-ext:1.1.8")
    // Android WorkManager (For our daily 24-hour background offline map audits)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Use a verified release build tag
}
/**
 * Copyright Microsoft Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// NOTE: this module requires the Android Gradle Plugin and AndroidX
// (Room, WorkManager, core-ktx, etc.), all served from Google's Maven
// repository (dl.google.com / maven.google.com). In sandboxes without
// access to that host, `:app` cannot be configured or built - only `:core`
// can (plain JVM, Maven Central only). See README.md for details.
plugins {
    id("com.android.application") version "8.5.2"
    kotlin("android") version "1.9.24"
    kotlin("kapt") version "1.9.24"
}

android {
    namespace = "com.microsoft.azure.storage.photosync.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.microsoft.azure.storage.photosync.app"
        minSdk = 25
        targetSdk = 29
        versionCode = 1
        versionName = "0.1.0-phase1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(project(":core"))
    // The existing Azure Storage Android SDK library, used to upload/download
    // block blob content via short-lived SAS URLs obtained from the API.
    // In this repo it is consumed as a project dependency on the legacy
    // `:microsoft-azure-storage` module; when this app module is extracted
    // into its own build it should instead depend on the published AAR
    // (`com.microsoft.azure.android:azure-storage-android`).
    implementation(files("${project.projectDir}/../../microsoft-azure-storage/build/libs/microsoft-azure-storage.jar"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.room:room-testing:2.6.1")
}

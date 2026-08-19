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

// This is an independent Gradle build, deliberately kept separate from the
// root azure-storage-android build (which still uses a legacy Gradle/AGP
// toolchain for the SDK library). See README.md for rationale and build
// instructions.
// Google's Maven repository (dl.google.com) is unreachable in some sandboxed
// build environments. `exclusiveContent` scopes it to only the
// com.android/androidx/Google Play Services groups it uniquely hosts, so
// that a network failure reaching it does not also break resolution of
// plugins/dependencies (e.g. the Kotlin Gradle plugin, JUnit) that are
// available from Maven Central / the Gradle Plugin Portal. This keeps the
// `:core` module buildable even when `:app` (which genuinely needs
// Google's repository for AndroidX/AGP) cannot be resolved.
pluginManagement {
    repositories {
        exclusiveContent {
            forRepository { google() }
            filter {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.gms.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { google() }
            filter {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.gms.*")
                includeGroupByRegex("androidx.*")
                includeGroup("com.google.android.material")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "photosync-app"

include(":core")
include(":app")

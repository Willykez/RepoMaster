plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {

    namespace = "com.willykez.repomaster"

    compileSdk = 36

    lint {
         checkReleaseBuilds = false
         abortOnError = false
    }


    defaultConfig {

        applicationId = "com.willykez.repomaster"

        minSdk = 24

        targetSdk = 36

        versionCode = 1

        versionName = "1.0.0"

        multiDexEnabled = true
    }


    // -------------------------------------------------------
    // Release signing
    //
    // Supplied by GitHub Actions:
    //
    // REPOMASTER_RELEASE_STORE_FILE
    // REPOMASTER_RELEASE_STORE_PASSWORD
    // REPOMASTER_RELEASE_KEY_ALIAS
    // REPOMASTER_RELEASE_KEY_PASSWORD
    // -------------------------------------------------------

    val releaseStoreFile =
        findProperty("REPOMASTER_RELEASE_STORE_FILE") as String?

    val releaseStorePassword =
        findProperty("REPOMASTER_RELEASE_STORE_PASSWORD") as String?

    val releaseKeyAlias =
        findProperty("REPOMASTER_RELEASE_KEY_ALIAS") as String?

    val releaseKeyPassword =
        findProperty("REPOMASTER_RELEASE_KEY_PASSWORD") as String?


    val hasReleaseSigning =
        !releaseStoreFile.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()


    signingConfigs {

        if (hasReleaseSigning) {

            create("release") {

                storeFile = file(releaseStoreFile!!)

                storePassword = releaseStorePassword

                keyAlias = releaseKeyAlias

                keyPassword = releaseKeyPassword
            }
        }
    }



    buildTypes {

        release {

            // Never use debug signing for release.
            if (hasReleaseSigning) {

                signingConfig =
                    signingConfigs.getByName("release")
            }


            isMinifyEnabled = true

            isShrinkResources = true


            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }
    }



    compileOptions {

        sourceCompatibility =
            JavaVersion.VERSION_17

        targetCompatibility =
            JavaVersion.VERSION_17

        isCoreLibraryDesugaringEnabled = true
    }



    kotlinOptions {

        jvmTarget = "17"
    }



    buildFeatures {

        compose = true
    }



    packaging {

        resources {

            excludes += listOf(

                "META-INF/DEPENDENCIES",

                "META-INF/LICENSE",

                "META-INF/LICENSE.txt",

                "META-INF/license.txt",

                "META-INF/NOTICE",

                "META-INF/NOTICE.txt",

                "META-INF/notice.txt",

                "META-INF/*.kotlin_module",

                "META-INF/INDEX.LIST",

                "META-INF/eclipse.inf",

                "about.html",

                "about_files/**",

                "plugin.properties"
            )
        }
    }
}



dependencies {


    coreLibraryDesugaring(
        "com.android.tools:desugar_jdk_libs:2.1.5"
    )


    val composeBom =
        platform(
            "androidx.compose:compose-bom:2026.06.00"
        )


    implementation(composeBom)


    implementation(
        "androidx.compose.ui:ui"
    )

    implementation(
        "androidx.compose.ui:ui-tooling-preview"
    )

    implementation(
        "androidx.compose.material3:material3"
    )

    implementation(
        "androidx.compose.material:material-icons-extended"
    )


    implementation(
        "androidx.compose.material:material"
    )


    debugImplementation(
        "androidx.compose.ui:ui-tooling"
    )


    // Activity + Navigation

    implementation(
        "androidx.activity:activity-compose:1.9.0"
    )

    implementation(
        "androidx.navigation:navigation-compose:2.9.2"
    )


    // Lifecycle

    implementation(
        "androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2"
    )

    implementation(
        "androidx.lifecycle:lifecycle-runtime-ktx:2.8.2"
    )

    implementation(
        "androidx.lifecycle:lifecycle-runtime-compose:2.8.2"
    )


    // Room

    val roomVersion = "2.6.1"

    implementation(
        "androidx.room:room-runtime:$roomVersion"
    )

    implementation(
        "androidx.room:room-ktx:$roomVersion"
    )

    ksp(
        "androidx.room:room-compiler:$roomVersion"
    )


    // DocumentFile

    implementation(
        "androidx.documentfile:documentfile:1.0.1"
    )


    // WorkManager

    implementation(
        "androidx.work:work-runtime-ktx:2.9.0"
    )


    // (Token encryption uses raw AndroidKeyStore/Cipher directly — see TokenCrypto.kt —
    // so no dependency on androidx.security:security-crypto is needed here.)


    // Coroutines

    implementation(
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1"
    )


    // Multidex

    implementation(
        "androidx.multidex:multidex:2.0.1"
    )


    // Core

    implementation(
        "androidx.core:core-ktx:1.13.1"
    )


    // JGit

    implementation(
        "org.eclipse.jgit:org.eclipse.jgit:6.7.0.202309050840-r"
    ) {

        exclude(
            group = "com.jcraft",
            module = "jsch"
        )
    }
}
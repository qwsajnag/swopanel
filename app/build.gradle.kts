import java.util.Properties

plugins {
    id("com.android.application") version "8.5.2"
}

// Optional release signing. Put keystore.properties next to settings.gradle.kts with:
//   storeFile=../release.keystore
//   storePassword=...
//   keyAlias=...
//   keyPassword=...
// Without it the release build falls back to the debug key, which is perfectly fine
// for a local LSPosed module (LSPosed does not care about the signature).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.dsh.ba4dkeepalive"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsh.ba4dkeepalive"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // The Xposed API artifact ships placeholder xposed metadata that would
            // clash with this module's own assets/xposed_init.
            excludes += setOf("META-INF/xposed/**", "assets/xposed_init")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // API 82 is implemented by LSPosed; compileOnly keeps it out of the APK.
    compileOnly("de.robv.android.xposed:api:82")
}

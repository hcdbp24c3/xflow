import java.io.File
import java.util.Properties
import org.gradle.api.attributes.Attribute

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.alchemistaloha.xflow"
    compileSdk = 36

    defaultConfig {
        // Same id as the Flutter build, so this app can replace it in place
        // and pick up its database, settings and login.
        applicationId = "com.alchemistaloha.xflow"
        minSdk = 24
        targetSdk = 36
        versionCode = 600
        versionName = "0.6.0"

        // CI test builds pass -PciIdSuffix=.kt so they install next to the
        // released app (and next to the Flutter CI build, which uses .ci).
        val ciIdSuffix = project.findProperty("ciIdSuffix") as String?
        if (!ciIdSuffix.isNullOrEmpty()) {
            applicationIdSuffix = ciIdSuffix
        }

        ndk {
            val abi = project.findProperty("abi") as String?
            if (!abi.isNullOrEmpty()) abiFilters += abi
        }
    }

    signingConfigs {
        // Same key.properties convention as the Flutter build. Without the
        // file the release build is signed with the debug key.
        create("release") {
            val keyPropertiesFile = rootProject.file("key.properties")
            if (keyPropertiesFile.exists()) {
                val keyProperties = Properties()
                keyPropertiesFile.inputStream().use { keyProperties.load(it) }
                storeFile = file(keyProperties["storeFile"] as String)
                storePassword = keyProperties["storePassword"] as String
                keyAlias = keyProperties["keyAlias"] as String
                keyPassword = keyProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (rootProject.file("key.properties").exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/{AL2.0,LGPL2.1}",
            "META-INF/*.kotlin_module",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-util")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-process:2.9.2")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")

    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

// Copies everything the release variant compiles against into one folder,
// so the sources can be type-checked outside Gradle. Not part of any build.
tasks.register("dumpCompileClasspath") {
    val jars = configurations.named("releaseCompileClasspath").map { cfg ->
        cfg.incoming.artifactView {
            attributes.attribute(
                Attribute.of("artifactType", String::class.java),
                "android-classes-jar",
            )
        }.files
    }
    val outDir = layout.buildDirectory.dir("classpath-dump")
    inputs.files(jars)
    outputs.dir(outDir)
    doLast {
        val out = outDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        jars.get().files.forEachIndexed { i, f ->
            if (f.isFile) {
                val tag = f.parentFile?.parentFile?.name.orEmpty().take(40)
                f.copyTo(File(out, "%03d-%s-%s".format(i, tag, f.name)), overwrite = true)
            }
        }
    }
}

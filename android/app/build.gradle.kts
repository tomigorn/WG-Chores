plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

// versionName = the server's version (server/Cargo.toml); beefy-build.sh passes it as -Pwgc.version
val appVersion: String = providers.gradleProperty("wgc.version").orNull
    ?: rootDir.resolve("../server/Cargo.toml").takeIf { it.isFile }?.readLines()
        ?.firstNotNullOfOrNull { Regex("""^version = "(.+)"""").find(it)?.groupValues?.get(1) }
    ?: error("app version unknown: build inside the WG-Chores repo or pass -Pwgc.version=X.Y.Z")
// same rule as AppVersion in the app: numbers before an optional "-suffix"
val appVersionCode: Int = appVersion.substringBefore('-').split('.').map {
    it.toIntOrNull() ?: error("app version $appVersion is not X.Y.Z")
}.let { parts ->
    require(parts.size == 3) { "app version $appVersion is not X.Y.Z" }
    val (major, minor, patch) = parts
    require(minor < 100 && patch < 100) { "app version $appVersion: minor and patch must stay below 100 for the versionCode" }
    major * 10000 + minor * 100 + patch
}

android {
    namespace = "ch.tomigorn.wgchores"
    compileSdk = 37

    defaultConfig {
        applicationId = "ch.tomigorn.wgchores"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    sourceSets {
        getByName("test").kotlin.srcDir("src/sharedTest/java")
        getByName("androidTest").kotlin.srcDir("src/sharedTest/java")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module")
    }
}

roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

// goldens live in src/, which beefy-build.sh does not copy back; build/outputs is
val exportScreenshots = tasks.register<Sync>("exportScreenshots") {
    from("src/test/screenshots")
    into(layout.buildDirectory.dir("outputs/screenshots"))
}
tasks.matching { it.name == "testDebugUnitTest" }.configureEach { finalizedBy(exportScreenshots) }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.browser)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.mockwebserver)
}

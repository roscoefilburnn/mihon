plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)
}

android {
    namespace = "mihon.core.panels"
}

dependencies {
    implementation(projects.coreMetadata)

    // Detectors expose suspend functions and check for cancellation between phases of a long,
    // CPU-bound detection pass, so this module needs coroutines on its own compile classpath
    // rather than inheriting it from :app.
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.tensorflow.lite)
    implementation(libs.opencv)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

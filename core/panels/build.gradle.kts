plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)
}

android {
    namespace = "mihon.core.panels"
}

dependencies {
    implementation(projects.coreMetadata)

    implementation(libs.tensorflow.lite)
    implementation(libs.opencv)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

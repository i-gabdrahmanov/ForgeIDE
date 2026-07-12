plugins {
    `java-library`
}

dependencies {
    api(project(":core"))
    implementation(libs.slf4j.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    // T38: the import → deploy → preflight chain test needs the real DefaultHarnessGuard.
    // Test-only edge, no cycle (runtime's main code never sees importer).
    testImplementation(project(":runtime"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

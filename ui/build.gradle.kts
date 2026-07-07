plugins {
    application
    alias(libs.plugins.javafx)
}

val javafxVersion = libs.versions.javafx.get()

javafx {
    version = javafxVersion
    modules("javafx.controls")
}

application {
    mainClass.set("dev.forgeide.ui.Launcher")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":runtime"))
    implementation(project(":importer"))
    implementation(libs.richtextfx)
    implementation(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.application.tasks.CreateStartScripts
import org.gradle.nativeplatform.MachineArchitecture
import org.gradle.nativeplatform.OperatingSystemFamily

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

// T30: per-OS distribution zips. The default `distZip` (from the `application` plugin,
// via the javafx plugin's auto-detected classifier) only ever contains JavaFX jars for the
// machine that ran the build — fine for `:ui:run`, wrong for something you hand to someone
// on a different OS/arch. Each target below resolves its own runtime classpath against a
// specific org.gradle.native.{operatingSystem,architecture} attribute pair — the same
// mechanism the javafx plugin itself uses (see JavaFXComponentMetadataRule, which tags every
// org.openjfx:javafx-* artifact with per-platform variants) — so a single machine can produce
// artifacts for every supported platform without needing a build agent on each OS
// (NFR-5: macOS + Linux, no Windows yet). Plain "org.openjfx:javafx-x:version:<classifier>"
// dependency notation does NOT work here: Gradle resolves those modules via Gradle Module
// Metadata variants, not plain classifier-qualified artifacts, and an explicit classifier
// collides with variant selection ("Cannot choose between the following variants").
data class DistTarget(val id: String, val taskSuffix: String, val osFamily: String, val arch: String)

val distTargets = listOf(
    DistTarget(id = "mac-aarch64", taskSuffix = "MacAarch64", osFamily = OperatingSystemFamily.MACOS, arch = MachineArchitecture.ARM64),
    DistTarget(id = "linux-x64", taskSuffix = "LinuxX64", osFamily = OperatingSystemFamily.LINUX, arch = MachineArchitecture.X86_64),
)

val javafxModules = listOf("base", "graphics", "controls")

val distZipTasks = distTargets.map { target ->
    val runtimeConfig = configurations.create("dist${target.taskSuffix}Runtime") {
        isCanBeConsumed = false
        description = "Runtime classpath for the ${target.id} distribution (os=${target.osFamily}, arch=${target.arch})"
        attributes {
            // Mirror the standard runtimeClasspath attributes (usage/category/libraryElements/bundling) —
            // without them, the OS/arch attributes below are ambiguous against every other unrelated
            // variant (javadoc, sources, compile, platform, ...) of the javafx-* modules, since a bare
            // custom configuration otherwise requests no Java-ecosystem attributes at all.
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class, Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class, LibraryElements.JAR))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class, Bundling.EXTERNAL))
            attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, objects.named(OperatingSystemFamily::class, target.osFamily))
            attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, objects.named(MachineArchitecture::class, target.arch))
        }
    }

    dependencies {
        add(runtimeConfig.name, project(":core"))
        add(runtimeConfig.name, project(":runtime"))
        add(runtimeConfig.name, project(":importer"))
        add(runtimeConfig.name, libs.richtextfx)
        add(runtimeConfig.name, libs.logback.classic)
        javafxModules.forEach { module ->
            add(runtimeConfig.name, "org.openjfx:javafx-$module:$javafxVersion")
        }
    }

    val startScripts = tasks.register<CreateStartScripts>("dist${target.taskSuffix}StartScripts") {
        applicationName = "ui"
        mainClass.set("dev.forgeide.ui.Launcher")
        outputDir = layout.buildDirectory.dir("dist-scripts/${target.id}").get().asFile
        classpath = files(tasks.named<Jar>("jar").flatMap { it.archiveFile }) + runtimeConfig
    }

    tasks.register<Zip>("dist${target.taskSuffix}Zip") {
        group = "distribution"
        description = "Собирает самодостаточный zip-дистрибутив для ${target.id} (os=${target.osFamily}, arch=${target.arch})."
        val distName = "ui-${project.version}-${target.id}"
        archiveFileName.set("$distName.zip")
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        into(distName) {
            into("lib") {
                from(tasks.named("jar"))
                from(runtimeConfig)
            }
            into("bin") {
                from(startScripts)
                filePermissions { unix("rwxr-xr-x") }
            }
        }
    }
}

tasks.register("platformDistZips") {
    group = "distribution"
    description = "Собирает per-OS дистрибутивы: ${distTargets.joinToString { it.id }}."
    dependsOn(distZipTasks)
}

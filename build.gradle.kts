allprojects {
    group = "dev.forgeide"
    version = "0.1.0"
}

// T26: line-coverage floor per module, snapshotted 2026-07-12 from the actual measured
// coverage (core 84.25%, runtime 83.08%, importer 90.52%; see docs/tasks/T26-ci-coverage.md)
// and floored to the whole percent below — not an invented target. Bump this up as coverage
// improves; never lower it to make a red build green.
val coverageMinimums = mapOf(
    "core" to 0.84,
    "runtime" to 0.83,
    "importer" to 0.90,
)

subprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }

        val coverageMinimum = coverageMinimums[name]
        if (coverageMinimum != null) {
            apply(plugin = "jacoco")

            tasks.named<JacocoReport>("jacocoTestReport") {
                dependsOn(tasks.named("test"))
                reports {
                    xml.required.set(true)
                    html.required.set(true)
                }
            }

            tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
                dependsOn(tasks.named("jacocoTestReport"))
                violationRules {
                    rule {
                        limit {
                            counter = "LINE"
                            minimum = coverageMinimum.toBigDecimal()
                        }
                    }
                }
            }

            tasks.named("check") {
                dependsOn(tasks.named("jacocoTestCoverageVerification"))
            }
        }
    }

    repositories {
        mavenCentral()
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

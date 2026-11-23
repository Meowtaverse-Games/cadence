plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.serialization.json)
}

tasks.test {
    useJUnit()
    val fixtures = rootProject.layout.projectDirectory.dir("../spec/fixtures")
    inputs.dir(fixtures)
    systemProperty("cadence.fixtures", fixtures.asFile.absolutePath)
    testLogging {
        events("passed", "skipped", "failed")
    }
}

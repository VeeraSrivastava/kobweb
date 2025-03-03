plugins {
    `kotlin-dsl`
    kotlin("jvm")
    id("com.varabyte.kobweb.internal.publish")
    `java-library`
    `java-gradle-plugin`
}

group = "com.varabyte.kobweb.gradle"
version = libs.versions.kobweb.get()

dependencies {
    implementation(kotlin("stdlib"))
    // Get access to Kotlin multiplatform source sets
    implementation(kotlin("gradle-plugin"))

    // For parsing code. Instead, use KSP someday? See also: Bug #4
    implementation(kotlin("compiler-embeddable"))

    // For generating code / html
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinx.html)

    // Export
    implementation(libs.cdt) // Run ChromeLauncher during export
    implementation(libs.jsoup)

    implementation(project(":common:kobweb-common"))
}

val DESCRIPTION = "A Gradle plugin that completes a user's Kobweb app"
gradlePlugin {
    plugins {
        create("kobwebApplication") {
            id = "com.varabyte.kobweb.application"
            displayName = "Kobweb Application Plugin"
            description = DESCRIPTION
            implementationClass = "com.varabyte.kobweb.gradle.application.KobwebApplicationPlugin"
        }
    }
}

kobwebPublication {
    // Leave artifactId blank. It will be set to the name of this module, and then the gradlePlugin step does some
    // additional tweaking that we don't want to interfere with.
    description.set(DESCRIPTION)
}

/**
 * Embed a copy of the latest Kobweb server, naming it server.jar and putting it into the project's resources/ dir, so
 * we can run it from the plugin at runtime.
 */
tasks.register<Copy>("copyServerJar") {
    dependsOn(":backend:server:shadowJar")

    val serverJarName = "server-${libs.versions.kobweb.get()}-all.jar"
    val serverJarFile = file("${project(":backend:server").buildDir}/libs/$serverJarName")

    from(file(serverJarFile))
    into(file("$projectDir/build/resources/main"))
    rename(serverJarName, "server.jar")
}
project.tasks.named("processResources") {
    dependsOn("copyServerJar")
}
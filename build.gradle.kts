import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.xml.XmlSlurper
import groovy.xml.slurpersupport.GPathResult
import groovy.xml.slurpersupport.NodeChildren
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.max
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileNotFoundException

val isCiBuild = providers.environmentVariable("CI").map { it.toBoolean() }.orElse(false).get()
val isSnapshot = providers.gradleProperty("isSnapshot").map { it.toBoolean() }.orElse(false).get()
val projectUrl = providers.gradleProperty("url")

val groupUrl = rootProject.group.toString().replace(".", "/")

val baseVersion = project.version.toString()
project.version = if (isSnapshot) "$baseVersion-SNAPSHOT" else if (!isCiBuild) "$baseVersion.local" else baseVersion

buildscript {
    dependencies {
        val kotlinVersion = file("generated/kotlin_version.txt").readText().trim()
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    }
}

plugins {
    alias(libs.plugins.leaf.loom)
    alias(libs.plugins.spotless)

    `maven-publish`
    signing
}

apply(plugin = "org.jetbrains.kotlin.jvm")

val libraryVersionsFile = "generated/library_versions.json"
val kotlinVersionFile = "generated/kotlin_version.txt"

val kotlinLib = "org.jetbrains.kotlin:kotlin-stdlib"
val libraries = listOf(
    kotlinLib,
    "org.jetbrains.kotlin:kotlin-stdlib-jdk8",
    "org.jetbrains.kotlin:kotlin-stdlib-jdk7",
    "org.jetbrains.kotlin:kotlin-reflect",

    "org.jetbrains.kotlinx:kotlinx-coroutines-core",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm",
    "org.jetbrains.kotlinx:kotlinx-coroutines-jdk8",
    "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm",
    "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm",
    "org.jetbrains.kotlinx:kotlinx-serialization-cbor-jvm",
    "org.jetbrains.kotlinx:atomicfu-jvm",
    "org.jetbrains.kotlinx:kotlinx-datetime-jvm",
    "org.jetbrains.kotlinx:kotlinx-io-core-jvm",
    "org.jetbrains.kotlinx:kotlinx-io-bytestring-jvm"
)

val parsedVersions = JsonSlurper().parse(file(libraryVersionsFile)) as Map<*, *>
val libVersions = parsedVersions.mapKeys { it.key.toString() }.mapValues { it.value.toString() }
val hasMissingLibVersion = !libVersions.keys.containsAll(libraries)
val kotlinVersionText = file(kotlinVersionFile).readText().trim()

println("Kotlin: $kotlinVersionText")
println("Libraries:")
libVersions.forEach { (k, v) ->
    println("\t$k:$v")
}

loom {
    mods {
        register("llk") {
            sourceSet(sourceSets.main.get())
        }
    }
}

val includeAndExpose = configurations.create("includeAndExpose")

configurations {
    api {
        extendsFrom(includeAndExpose)
    }

    include {
        extendsFrom(includeAndExpose)
    }
}

configurations.all {
    resolutionStrategy {
        failOnNonReproducibleResolution()
    }
}

repositories {
    maven {
        name = "Leaf"
        url = uri("https://maven.aoqia.dev/releases/")
    }

    mavenLocal()
}

dependencies {
    zomboid(libs.zomboid)
    implementation(libs.leaf.loader)
    // TODO(leaf): Uncomment when loader junit is working
    // testImplementation(libs.leaf.loader.junit)

    testImplementation("org.jetbrains.kotlin:kotlin-test")

    if (hasMissingLibVersion) {
        println("Contains missing library version, run updateLibraryVersions task!")
    } else {
        libraries.forEach {
            includeAndExpose("$it:${libVersions[it]}")
        }
    }
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

spotless {
    kotlin {
        licenseHeaderFile(rootProject.file("HEADER"))
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

tasks.withType<ProcessResources> {
    inputs.property("version", "${version}+kotlin.${kotlinVersionText}")
    inputs.property("url", projectUrl)

    doLast {
        filesMatching("leaf.mod.json") {
            expand(
                mapOf(
                    "version" to "${version}+kotlin.${kotlinVersionText}",
                    "url" to projectUrl.get(),
                )
            )
        }
    }
}

tasks.withType<Sign>().configureEach {
    enabled = isCiBuild && !isSnapshot
}

val jarTask = tasks.named<Jar>("jar") {
    val archivesName = project.base.archivesName.get()
    from("LICENSE") {
        rename { "${it}_${archivesName}" }
    }
}

val processTemplatesTask = tasks.register<Copy>("processTemplates") {
    group = "documentation"
    doNotTrackState("Writes generated docs directly into the project root, which overlaps with .gradle")

    val template = mutableMapOf(
        "MOD_VERSION" to version,
        "LOADER_VERSION" to libs.versions.leaf.loader.get(),
    )
    libraries.forEach {
        val key = it.split(":", limit = 2)[1].replace("-", "_").uppercase(Locale.ROOT) + "_VERSION"
        template[key] = libVersions[it]!!
    }

    doFirst {
        if (hasMissingLibVersion) {
            throw GradleException("Contains missing library version, run updateLibraryVersions task first!")
        }
    }

    from(file("templates"))
    include("**/*.template.md")
    filesMatching("**/*.template.md") {
        name = name.replace("template.", "")
        expand(template)
    }
    destinationDir = rootDir
}

val updateVersionsTask = tasks.register("updateVersions") {
    group = "update"

    dependsOn(updateLibraryVersions)
}

val updateLibraryVersions = tasks.register("updateLibraryVersions") {
    group = "update"

    doFirst {
        val output = mutableMapOf<String, String>()
        val versionRegex = Regex("<version>(.+?)</version>")

        for (lib in libraries) {
            val split = lib.split(":", limit = 2)
            val groupPath = split[0].replace(".", "/")
            val artifact = split[1]
            val xml = URI("https://repo1.maven.org/maven2/${groupPath}/${artifact}/maven-metadata.xml").toURL().readText()
            val versions = versionRegex.findAll(xml).map { it.groupValues[1] }.toList()

            val latest = versions.filter { it.matches(Regex("""\d+(\.\d+)*""")) }.maxWithOrNull(Comparator { a, b ->
                val left = a.split(".").map { it.toIntOrNull() ?: 0 }
                val right = b.split(".").map { it.toIntOrNull() ?: 0 }
                for (i in 0 until max(left.size, right.size)) {
                    val l = left.getOrElse(i) { 0 }
                    val r = right.getOrElse(i) { 0 }
                    if (l != r) return@Comparator l.compareTo(r)
                }
                0
            }) ?: continue

            println("$lib = $latest")
            output[lib] = latest

            if (lib == kotlinLib) {
                file(kotlinVersionFile).writeText(latest, StandardCharsets.UTF_8)
            }
        }

        val json = JsonBuilder(output).toPrettyString()
        file(libraryVersionsFile).writeText(json, StandardCharsets.UTF_8)
    }
}

val checkVersion = tasks.register("checkVersion") {
    description = "Ensures that the version being released has not already been released"

    doFirst {
        val xml = try {
            URI.create("https://maven.aoqia.dev/${if (isSnapshot) "snapshots" else "releases"}/${
                rootProject.group.toString().replace(".", "/")
            }/${rootProject.name}/maven-metadata.xml").toURL().readText()
        } catch (_: FileNotFoundException) {
            null
        }

        if (xml != null) {
            val metadata = XmlSlurper().parseText(xml)

            val versioning = metadata.getProperty("versioning") as GPathResult
            val versions = versioning.getProperty("versions") as GPathResult
            val versionText = (versions.getProperty("version") as NodeChildren).map { it.toString() }

            if (versionText.contains(version)) {
                throw RuntimeException("$version has already been released!")
            }
        }
    }
}

val publishTask = tasks.named("publish") {
    dependsOn(checkVersion)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()

            from(components["java"])

            pom {
                name = rootProject.name
                group = rootProject.group
                description = rootProject.description
                url = projectUrl.get()
                inceptionYear = "2026"

                licenses {
                    license {
                        name = "Apache-2.0"
                        url = "https://spdx.org/licenses/Apache-2.0.html"
                    }
                }
            }
        }

        repositories {
            maven {
                name = "leaf"
                url = uri("https://maven.aoqia.dev/${if (isSnapshot) "snapshots" else "releases"}")

                credentials {
                    username = providers.gradleProperty("mavenUsername").orNull
                    password = providers.gradleProperty("mavenPassword").orNull
                }

                authentication {
                    create<BasicAuthentication>("basic")
                }
            }
        }
    }

    signing {
        isRequired = isCiBuild and !isSnapshot

        val signingKey = providers.gradleProperty("signingKey")
        val signingPassword = providers.gradleProperty("signingPassword")
        if (signingKey.isPresent && signingPassword.isPresent) {
            useInMemoryPgpKeys(signingKey.get(), signingPassword.get())
        }

        sign(publishing.publications)
    }
}

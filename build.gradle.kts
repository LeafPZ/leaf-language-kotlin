import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.max
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    dependencies {
        val kotlinVersion = file("generated/kotlin_version.txt").readText().trim()
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    }
}

plugins {
    alias(libs.plugins.leaf.loom)
    alias(libs.plugins.spotless)
    id("maven-publish")
}

apply(plugin = "org.jetbrains.kotlin.jvm")

group = project.group!!

val env: Map<String, String> = System.getenv()
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

version = "${project.version}+kotlin.${kotlinVersionText}" + (if (env["GITHUB_ACTIONS"] != null) "" else ".local")

loom {
    mods {
        register("llk") {
            sourceSet(sourceSets.main.get())
        }
    }
}

val includeAndExpose: Configuration = configurations.create("includeAndExpose")

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
    testImplementation(libs.leaf.loader)
    //testImplementation(libs.leaf.loader.junit) // junit out-of-order :(
    testImplementation("org.jetbrains.kotlin:kotlin-test")

    if (hasMissingLibVersion) {
        println("Contains missing library version, run updateLibraryVersions task!")
    } else {
        libraries.forEach {
            includeAndExpose("$it:${libVersions[it]}")
        }
    }
}

tasks.withType<ProcessResources> {
    inputs.property("version", project.version.toString())

    filesMatching("leaf.mod.json") {
        expand(mapOf("version" to project.version.toString()))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
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

tasks.named<Jar>("jar") {
    val archivesName = project.base.archivesName.get()
    from("LICENSE") {
        rename { "${it}_${archivesName}" }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Copy>("processMDTemplates") {
    group = "documentation"
    doNotTrackState("Writes generated docs directly into the project root, which overlaps with .gradle")

    val template = mutableMapOf<String, Any>(
        "MOD_VERSION" to "${project.version}+kotlin.${kotlinVersionText}",
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

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = project.group.toString()
            artifactId = project.name.lowercase(Locale.ROOT)
            version = project.version.toString()

            from(components["java"])
        }
    }

    repositories {
        mavenLocal()
        if (env["MAVEN_URL"] != null) {
            maven {
                url = uri(env["MAVEN_URL"]!!)
                credentials {
                    username = env["MAVEN_USERNAME"]
                    password = env["MAVEN_PASSWORD"]
                }
            }
        }
    }
}

// needs a maven page
// A task to ensure that the version being released has not already been released.
val checkVersion: TaskProvider<Task> = tasks.register("checkVersion") {
    doFirst {
//        val xml = URI("https://maven.fabricmc.net/net/fabricmc/fabric-language-kotlin/maven-metadata.xml").toURL().readText()
//        val versions = Regex("<version>(.+?)</version>").findAll(xml).map { it.groupValues[1] }.toList()
//        if (versions.contains(project.version.toString())) {
//            throw RuntimeException("${project.version} has already been released!")
//        }
    }
}

val updateLibraryVersions: TaskProvider<Task> = tasks.register("updateLibraryVersions") {
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

val updateVersions: TaskProvider<Task> = tasks.register("updateVersions") {
    group = "update"
    dependsOn(updateLibraryVersions)
}

tasks.named("publish") { dependsOn(checkVersion) }

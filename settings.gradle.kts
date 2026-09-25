pluginManagement {
	repositories {
		maven {
			name = "Fabric"
			url = uri("https://maven.fabricmc.net/")
		}
		maven("https://maven.aoqia.dev/releases")
		maven("https://maven.aoqia.dev/snapshots")
		mavenCentral()
		gradlePluginPortal()
	}
}

rootProject.name = "leaf-language-kotlin"
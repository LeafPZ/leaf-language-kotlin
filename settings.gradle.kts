pluginManagement {
	repositories {
		maven {
			name = "Fabric"
			url = uri("https://maven.fabricmc.net/")
		}
        maven {
            name = "aoqia"
            url = uri("https://maven.aoqia.dev/releases")

            mavenContent {
                releasesOnly()
            }
        }
        maven {
            name = "aoqia-snapshots"
            url = uri("https://maven.aoqia.dev/snapshots")

            mavenContent {
                snapshotsOnly()
            }
        }
		mavenCentral()
		gradlePluginPortal()
	}
}

rootProject.name = providers.gradleProperty("name").get()
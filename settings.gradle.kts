pluginManagement {
  val kotlinVersion = providers.gradleProperty("kotlinVersion").getOrElse("2.4.21")
  plugins {
    kotlin("jvm") version kotlinVersion
    kotlin("kapt") version kotlinVersion
    kotlin("plugin.spring") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion
  }
}

rootProject.name = "mcp-openai"

plugins {
  id("java")
}

var jarName = "bukec"

group = "com.github.ieatglu3"
version = "1.0.0"

repositories {
  mavenCentral()
  maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
}

dependencies {
  compileOnly("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT") {
    exclude(group = "net.md-5", module = "bungeecord-chat")
  }
  testImplementation(platform("org.junit:junit-bom:5.10.0"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
  sourceCompatibility = JavaVersion.toVersion(21)
}

tasks {

  withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release = 8
  }

  jar {
    archiveBaseName = jarName
    version = project.version
  }

  test {
    useJUnitPlatform()
  }
}
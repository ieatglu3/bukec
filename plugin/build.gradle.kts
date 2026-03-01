plugins {
  id("java")
  id("maven-publish")
}

var jarName = "bukec-plugin"

group = "com.github.ieatglu3"
version = "1.0.0"

repositories {
  mavenCentral()
  maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
}

dependencies {
  implementation(project(":api"))
  compileOnly("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT") {
    exclude(group = "net.md-5", module = "bungeecord-chat")
  }
  testImplementation(platform("org.junit:junit-bom:5.10.0"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
  sourceCompatibility = JavaVersion.toVersion(21)
  withSourcesJar()
  withJavadocJar()
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

publishing {
  publications {
    create<MavenPublication>("mavenJava") {
      from(components["java"])
      groupId = project.group.toString()
      artifactId = jarName
      version = project.version.toString()
    }
  }
}

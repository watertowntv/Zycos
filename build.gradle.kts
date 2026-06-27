import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    `java-library`
    kotlin("jvm") version "2.4.0"

    id("com.gradleup.shadow") version "9.4.2"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
    id("maven-publish")
}

group = properties["pluginGroup"]!!
version = properties["pluginVersion"]!!

repositories {
    mavenCentral()
    gradlePluginPortal()

    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))

    compileOnly("org.junit.jupiter:junit-jupiter:6.1.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")

    paperweight.paperDevBundle("26.1.2.build.+")
}

java {
    withSourcesJar()

    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }
    javadoc {
        options.encoding = "UTF-8"
    }
    compileKotlin {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
    }
    processResources {
        filesMatching("**/*.yml") {
            expand(project.properties)
        }
    }
    shadowJar {
        archiveClassifier.set("dist")
    }
    build {
        dependsOn(shadowJar)
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = project.group.toString()
            artifactId = "zycos"
            version = project.version.toString()

            from(components["java"])
        }
    }
    repositories {
        mavenLocal()
    }
}

paperweight {
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

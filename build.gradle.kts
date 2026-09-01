import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    `java-library`
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"

    id("com.gradleup.shadow") version "9.6.1"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.22"
    id("maven-publish")
}

group = providers.gradleProperty("group").get()

repositories {
    mavenCentral()
    gradlePluginPortal()

    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")

    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.11.0")

    paperweight.paperDevBundle("26.2.build.+")


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
    test {
        useJUnitPlatform()
    }
    processResources {
        filteringCharset = "UTF-8"
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

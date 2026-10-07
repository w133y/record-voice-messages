plugins {
    java
}

group = "io.github.w133y"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.maxhenkel.de/repository/public")
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    // Region/entity/async schedulers from paper-api cover Folia too, no folia-api needed
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    // Static audio channels with addTarget() require API 2.6.0+
    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.6.0")
    // Optional: turns [vn:id] tokens sent through chat plugins into clickable buttons
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
}

java {
    // Paper 26.1+ runs on Java 25
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

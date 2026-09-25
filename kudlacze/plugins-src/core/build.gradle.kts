plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "pl.kudlacze"
version = "1.0.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")      // paper-api
    maven("https://maven.enginehub.org/repo/")                      // WorldGuard / WorldEdit
    maven("https://repo.extendedclip.com/releases/")                // PlaceholderAPI
    maven("https://repo.opencollab.dev/maven-snapshots/")           // Floodgate / Cumulus
    maven("https://repo.opencollab.dev/main/")
    maven("https://jitpack.io") {                                   // VaultAPI
        content { includeGroup("com.github.MilkBowl") }
    }
}

val paperApi = "26.2.build.129-stable"

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApi")
    compileOnly("net.luckperms:api:5.5")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.19") { isTransitive = false }
    compileOnly("com.sk89q.worldguard:worldguard-core:7.0.19") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.4.5") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.5") { isTransitive = false }
    compileOnly("me.clip:placeholderapi:2.12.3")
    compileOnly("org.geysermc.floodgate:api:2.2.5-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") { isTransitive = false }

    // wbudowane w jar (shadow + relokacja)
    implementation("com.zaxxer:HikariCP:7.1.0") { exclude(group = "org.slf4j") }
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.10") { exclude(group = "com.github.waffle") }

    testImplementation("io.papermc.paper:paper-api:$paperApi")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.2:4.116.1")
    testImplementation("net.luckperms:api:5.5")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.h2database:h2:2.5.250")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Duser.timezone=UTC", "-Dfile.encoding=UTF-8")
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("com.zaxxer.hikari", "pl.kudlacze.core.lib.hikari")
    relocate("org.mariadb.jdbc", "pl.kudlacze.core.lib.mariadb")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/maven/**")
}

tasks.jar { enabled = false }
tasks.assemble { dependsOn(tasks.shadowJar) }

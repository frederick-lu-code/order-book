plugins {
    java
    application
    id("me.champeau.jmh") version "0.7.2"
}

group = "orderbook"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "orderbook.cli.Main"
}

// Hand-written sources must compile cleanly; JMH's generated sources are exempt.
listOf("compileJava", "compileTestJava", "compileJmhJava").forEach { name ->
    tasks.named<JavaCompile>(name) {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }
}

tasks.test {
    useJUnitPlatform()
}

jmh {
    jmhVersion = "1.37"
}

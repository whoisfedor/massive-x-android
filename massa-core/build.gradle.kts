plugins { kotlin("jvm") }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    // Reuse the reviewed Base58Check codec, never Bitcoin transaction or wallet APIs.
    implementation("org.bitcoinj:bitcoinj-core:0.17.1") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    testImplementation("junit:junit:4.13.2")
    implementation("com.google.code.gson:gson:2.13.2")
}
tasks.test { useJUnit(); testLogging { events("failed", "skipped"); showStandardStreams = false } }

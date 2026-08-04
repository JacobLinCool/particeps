plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

application {
    mainClass = "cool.linc.androiddatacollector.researcher.MainKt"
}

tasks.named<JavaExec>("run") {
    workingDir(rootProject.projectDir)
}

tasks.withType<Test>().configureEach {
    systemProperty("adc.repository.root", rootProject.projectDir.absolutePath)
}

dependencies {
    implementation(project(":core:crypto"))
    implementation(project(":core:export"))
    implementation(project(":core:protocol"))
    implementation(project(":core:study-definition"))
    implementation(libs.gson)
    testImplementation(libs.junit4)
}

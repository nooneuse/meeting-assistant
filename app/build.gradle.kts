import java.time.LocalDate
import java.time.format.DateTimeFormatter

plugins { id("com.android.application") }
// Stamp the build machine's local calendar date into the installed version.
val buildDate = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
android {
    namespace = "io.github.meetingassistant"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "io.github.meetingassistant"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0+$buildDate"
        testInstrumentationRunner = "io.github.meetingassistant.android.SmokeInstrumentation"
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    bundle { language { enableSplit = false } }
    buildTypes { getByName("release") { isMinifyEnabled = false } }
    lint { warningsAsErrors = false; abortOnError = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation(project(":core"))
    implementation("com.google.android.material:material:1.13.0")
    // Use only the low-level UVC/USB APIs; no helper UI, recording, or appcompat dependency.
    implementation("com.herohan:UVCAndroid:1.0.13") { isTransitive = false }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    systemProperty("meeting.preview.dir", layout.buildDirectory.dir("reports/ui").get().asFile.absolutePath)
}

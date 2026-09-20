plugins { id("com.android.library"); kotlin("android") }

android { namespace = "io.convertmax.sdk"; compileSdk = 35
    defaultConfig { minSdk = 23; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    testImplementation(kotlin("test"))
    testImplementation("org.mockito:mockito-core:5.14.2")
}

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

/* Configuración privada: propiedad de Gradle (-P o %USERPROFILE%\.gradle\gradle.properties),
   variable de entorno (GitHub Actions) o local.properties (ignorado por git). Nunca en el repositorio. */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(name: String): String =
    (providers.gradleProperty(name).orNull ?: System.getenv(name) ?: localProps.getProperty(name) ?: "").trim()

val serverUrl = cfg("MINUBE_SERVER_URL").trimEnd('/')
val githubRepo = cfg("MINUBE_GITHUB_REPO")
val keystoreFile = cfg("MINUBE_KEYSTORE_FILE")

android {
    namespace = "com.carlos.minube"
    compileSdk = 37          // androidx.core 1.19 lo exige; no cambia el comportamiento (eso es targetSdk)

    defaultConfig {
        applicationId = "com.carlos.minube"   // no cambiar nunca: si cambia, no se puede actualizar encima
        minSdk = 26
        targetSdk = 35
        versionCode = cfg("MINUBE_VERSION_CODE").toIntOrNull() ?: 1
        versionName = cfg("MINUBE_VERSION_NAME").ifEmpty { "0.0.0-dev" }

        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
    }

    signingConfigs {
        create("release") {
            if (keystoreFile.isNotEmpty()) {
                storeFile = file(keystoreFile)
                storePassword = cfg("MINUBE_KEYSTORE_PASSWORD")
                keyAlias = cfg("MINUBE_KEY_ALIAS")
                keyPassword = cfg("MINUBE_KEY_PASSWORD")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // La versión de pruebas convive con la de release en el mismo móvil (otro paquete, otro nombre).
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

/* Guardia: la versión de release no se compila sin dirección del servidor ni keystore. */
val checkReleaseConfig = tasks.register("checkReleaseConfig") {
    val url = serverUrl
    val ks = keystoreFile
    doLast {
        if (url.isEmpty()) throw GradleException(
            "Falta MINUBE_SERVER_URL. Ponla en %USERPROFILE%\\.gradle\\gradle.properties (local) " +
                "o en la variable SERVER_URL del repositorio (GitHub Actions). Ver README.md."
        )
        if (!url.startsWith("https://")) throw GradleException(
            "MINUBE_SERVER_URL debe empezar por https:// (usa el nombre .ts.net de Tailscale, no la IP). Valor actual: $url"
        )
        if (ks.isEmpty() || !File(ks).exists()) throw GradleException(
            "Falta la keystore de release (MINUBE_KEYSTORE_FILE). Sin ella no se puede firmar. Ver README.md."
        )
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseConfig) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.work.runtime.ktx)
}

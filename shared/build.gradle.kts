import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidxRoom3)
}

kotlin {
    jvm()

    android {
        namespace = "com.appfusion.product.shared"
        compileSdk = 36
        minSdk = 26
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    val iosTargets = listOf(
        iosArm64(),
        iosSimulatorArm64(),
    )
    iosTargets.forEach { target ->
        target.binaries.framework {
            baseName = "AppFusionShared"
            isStatic = true
        }
    }

    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            implementation(libs.androidx.room3.runtime)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

dependencies {
    add("kspJvm", libs.androidx.room3.compiler)
    add("kspAndroid", libs.androidx.room3.compiler)
    add("kspIosArm64", libs.androidx.room3.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room3.compiler)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

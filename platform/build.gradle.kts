plugins {
    `java-platform`
    `maven-publish`
}

group = "com.kelvsyc.internal"

javaPlatform {
    // Allow for dependencies on other platforms (including BOMs)
    allowDependencies()
}

dependencies {
    api(platform(libs.aws.java.sdk.bom))
    api(platform(libs.aws.kotlin.sdk.bom))
    api(platform(libs.azure.sdk.bom))
    api(platform(libs.commons.numbers.bom))
    api(platform(libs.google.cloud.libraries.bom))
    // Not a direct dependency of any component; pins the versions of jackson-databind/jackson-core,
    // jsoup and freemarker pulled in transitively by org.jetbrains.dokka:dokka-core/dokka-base past
    // their known vulnerable ranges.
    api(platform(libs.jackson.bom))
    api(platform(libs.kotest.bom))
    api(platform(libs.kotlin.gradle.plugins.bom))
    api(platform(libs.kotlinx.coroutines.bom))
    api(platform(libs.netty.bom))
    api(platform(libs.okhttp.bom))
    api(platform(libs.opentelemetry.bom))
    api(platform(libs.retrofit.bom))

    constraints {
        api(libs.aws.lambda.java.core)
        api(libs.azure.resourcemanager.appconfiguration)
        api(libs.azure.resourcemanager.appcontainers)
        api(libs.commons.collections4)
        api(libs.commons.lang)
        api(libs.freemarker)
        api(libs.httpclient5)
        api(libs.jsoup)
        api(libs.kotlinx.datetime)
        api(libs.moshi)
        api(libs.moshi.kotlin)
        api(libs.msgpack.core)
        api(libs.okio)
        api(libs.pkl.core)
        api(libs.snakeyaml.engine)
        api(libs.vault.java.driver)
        api(libs.woodstox)
    }
}

publishing {
    publications {
        create<MavenPublication>("platform") {
            from(components["javaPlatform"])
        }
    }
}

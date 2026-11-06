plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    testImplementation(kotlin("test"))
}

val publishedBridge = rootProject.file(
    "shared/bridge/windows/bin/Release/net8.0-windows10.0.19041.0/win-x64/publish/FastDropBridge.exe"
)
val stageBridgeResources by tasks.registering(Copy::class) {
    from(publishedBridge)
    into(layout.buildDirectory.dir("bridgeResources/windows/bridge/windows"))
    doFirst { check(publishedBridge.isFile) {
        "Publish the Windows bridge before packaging: dotnet publish shared/bridge/windows/FastDropBridge.csproj -c Release -r win-x64 --self-contained true"
    } }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(stageBridgeResources) }

compose.desktop {
    application {
        mainClass = "com.fastdrop.desktop.MainKt"
        nativeDistributions {
            outputBaseDir.set(layout.buildDirectory.dir("compose-final"))
            appResourcesRootDir.set(layout.buildDirectory.dir("bridgeResources"))
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi)
            packageName = "FastDrop"
            packageVersion = "1.0.0"
        }
    }
}

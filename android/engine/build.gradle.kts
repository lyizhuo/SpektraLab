// android/engine/build.gradle.kts -- libspektralab.so: the upstream engine,
// the host file I/O units and the JNI facade, in one shared library.
//
// The engine itself is *not* copied in here. CMake is handed the repository
// root and does `add_subdirectory($SPEKTRALAB_REPO/engine engine)`, so the
// .so and the engine's own tests are built from one set of sources. See
// android/README.md for what that means for upstream sync.
import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.android.library)
}

// --- where things are ------------------------------------------------------

val repoRoot: File =
    (findProperty("spektralab.repo") as String?)?.let { file(it) } ?: rootProject.projectDir.parentFile
val depsDir: File =
    (findProperty("spektralab.depsDir") as String?)?.let { file(it) } ?: File(repoRoot, "build/deps")
val vulkanHeaders = File(depsDir, "Vulkan-Headers/include")
val librawSource = File(depsDir, "LibRaw-0.22.2")
val shaderSourceDir = File(repoRoot, "engine/src/shaders/vulkan")
val resourcesDir = File(repoRoot, "engine/resources")
val licenseDir = File(repoRoot, "license")

// The NDK ships glslc (shader-tools/<host>/glslc); a Vulkan SDK install has
// both glslc and glslangValidator. Either compiles these kernels; glslc is
// preferred because it is part of the NDK on every host, which is what makes
// a Windows Android Studio build work without a separate Vulkan SDK.
val ndkVersionString = "28.2.13676358"
val hostTag: String = when {
    OperatingSystem.current().isWindows -> "windows-x86_64"
    OperatingSystem.current().isMacOsX -> "darwin-x86_64"
    else -> "linux-x86_64"
}
fun ndkDir(): File {
    val explicit = System.getenv("ANDROID_NDK_HOME")
    if (explicit != null && File(explicit).isDirectory) return File(explicit)
    val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: File(System.getProperty("user.home"), "Android/Sdk").absolutePath
    return File(sdk, "ndk/$ndkVersionString")
}

val shaderTool: File = run {
    (findProperty("spektralab.shaderTool") as String?)?.let { return@run file(it) }
    val candidates = listOf(
        File(ndkDir(), "shader-tools/$hostTag/glslc"),
        File(ndkDir(), "shader-tools/$hostTag/glslc.exe"),
        System.getenv("VULKAN_SDK")?.let { File(it, "bin/glslc") },
        System.getenv("VULKAN_SDK")?.let { File(it, "bin/glslangValidator") },
        File(System.getProperty("user.home"), "tools/bin/glslangValidator"),
    ).filterNotNull()
    candidates.firstOrNull { it.isFile && it.canExecute() }
        ?: candidates.firstOrNull { it.isFile }
        ?: candidates.first()
}

// --- SPIR-V ----------------------------------------------------------------
//
// Compiled at build time, never shipped as GLSL: `spk_engine_create` loads
// `resources_dir/vulkan/*.spv`. The kernel list is the shader directory
// itself, so a kernel added upstream is compiled here without an edit, and a
// kernel that fails to compile fails the build (never a silent skip: an
// unbuilt .spv would turn into a runtime "missing resource" instead).
val shaderOutDir = layout.buildDirectory.dir("generated/shaders/vulkan")

val compileVulkanShaders = tasks.register("compileVulkanShaders") {
    group = "build"
    description = "Compiles engine/src/shaders/vulkan/*.comp to SPIR-V for the assets."
    inputs.dir(shaderSourceDir)
    inputs.file(shaderTool)
    outputs.dir(shaderOutDir)
    doLast {
        val out = shaderOutDir.get().asFile
        val stale = out.listFiles()?.filter { it.extension == "spv" } ?: emptyList()
        stale.forEach { it.delete() }
        val sources = shaderSourceDir.listFiles { f: File -> f.isFile && f.extension == "comp" }?.sorted()
            ?: error("no shaders in $shaderSourceDir")
        val viaGlslc = shaderTool.name.startsWith("glslc")
        for (shader in sources) {
            val spirv = File(out, shader.nameWithoutExtension + ".spv")
            // `providers.exec` rather than `project.exec`: Gradle 9 removed the
            // latter from Kotlin build scripts, and this keeps the task's
            // inputs/outputs the only configuration-cache-relevant state.
            val run = providers.exec {
                commandLine(
                    buildList {
                        add(shaderTool.absolutePath)
                        if (viaGlslc) {
                            add("--target-env=vulkan1.0")
                        } else {
                            add("-V")
                            add("--target-env")
                            add("vulkan1.0")
                        }
                        add("-I${shaderSourceDir.absolutePath}")
                        add("-o")
                        add(spirv.absolutePath)
                        add(shader.absolutePath)
                    }
                )
                isIgnoreExitValue = true
            }
            val result = run.result.get()
            if (result.exitValue != 0) {
                throw GradleException(
                    "shader compilation failed (${result.exitValue}) for ${shader.name}: " +
                        run.standardError.asText.get()
                )
            }
            if (!spirv.isFile || spirv.length() == 0L) {
                throw GradleException("shader compilation produced no SPIR-V for ${shader.name}")
            }
        }
        logger.lifecycle("compileVulkanShaders: ${sources.size} kernels -> ${out.relativeTo(repoRoot)}")
    }
}

// --- assets ---------------------------------------------------------------
//
// One asset tree, `assets/engine/**`, which the app unpacks to
// `filesDir/engine` and hands to `spk_engine_create`:
//   engine/{profiles,io,*.json,spektrafilm_constants.bin}  the baked resources
//   engine/vulkan/*.spv                                    the kernels
//   engine/license/**                                      the license texts
val syncEngineAssets = tasks.register<Sync>("syncEngineAssets") {
    group = "build"
    description = "Stages the engine resources, SPIR-V and license texts as assets."
    dependsOn(compileVulkanShaders)
    into(layout.buildDirectory.dir("generated/engineAssets"))
    from(resourcesDir) { into("engine") }
    from(shaderOutDir) { into("engine/vulkan") }
    from(licenseDir) { into("engine/license") }
}

// AGP's SourceSet API refuses Provider instances ("Android Studio cannot tell
// if a provider is generated or static"), so the directory is added as a
// FileCollection that *names its producer*: that is what makes every consumer
// of the source set (the asset merger and the lint model among them) wait for
// `syncEngineAssets` instead of discovering the ordering by accident.
val engineAssetsDir = File(layout.buildDirectory.get().asFile, "generated/engineAssets")
val engineAssets = files(engineAssetsDir).builtBy(syncEngineAssets)

android {
    namespace = "com.spektralab.engine"
    compileSdk = 36
    ndkVersion = ndkVersionString

    defaultConfig {
        minSdk = 29
        // Both ABIs the plan ships: every current phone and tablet is
        // arm64-v8a, and x86_64 is what the emulator runs, which is how the
        // engine gets exercised before it meets hardware.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DSPEKTRALAB_REPO=${repoRoot.absolutePath}",
                    "-DSPEKTRALAB_VULKAN_HEADERS=${vulkanHeaders.absolutePath}",
                    "-DSPEKTRALAB_LIBRAW_SOURCE=${librawSource.absolutePath}",
                    "-DSPEKTRALAB_GLSLANG=${shaderTool.absolutePath}",
                    "-DANDROID_STL=c++_static",
                )
            }
        }
    }

    buildTypes {
        // AGP would configure the debug variant as CMAKE_BUILD_TYPE=Debug,
        // which is -O0: every measurement taken on such a build is void, and
        // `nativeBuildInfo()` is what makes that visible at run time.
        // RelWithDebInfo keeps the engine optimised while staying debuggable;
        // the performance baselines are taken on `release` only.
        getByName("debug") {
            externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=RelWithDebInfo" } }
        }
        getByName("release") {
            externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            // Unused: the version is the SDK's, see android/README.md.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
        prefab = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    sourceSets.getByName("main") {
        assets.srcDir(engineAssets)
    }

    // The device tests read android/testdata (the cross-platform fixture the
    // plan names) out of the *test* APK; the tree is shared with the app's
    // tests rather than copied into each module.
    sourceSets.getByName("androidTest") {
        assets.srcDir(file("../testdata"))
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}


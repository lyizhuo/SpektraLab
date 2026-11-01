# SpektraLab → Android 迁移方案

**状态**：规划稿，未开始写代码。
**基线**：从 `origin/main`（`267ee24`）克隆，逐行读过 `engine/**`、`windows_UI/**`、`ARCHITECTURE.md` 与 `AGENTS.md`。
**重要前提**：真正的跨平台代码在 `Windows_Linux` 分支（比 `main` 领先 52 个提交），**Android 分支必须从它切出，不要从 `main` 切** —— 见 §1.4。

本文件不修改 `AGENTS.md` / `ARCHITECTURE.md` / `API-SPEC-*` / `CONTRACT-*`。若方案被采纳，建议随后在其中登记一条 Android 入口。

---

## 0. 结论摘要

| 问题 | 结论 |
|---|---|
| 要不要用 MNN / NCNN 推理框架 | **不要。** 引擎里没有神经网络（33 个节点全是解析式计算，无权重、无 ONNX/TFLite）。换框架只会更慢。详见 §3 |
| 那性能靠什么 | 沿用它**已经写好的 34 个 Vulkan Compute kernel**；性能瓶颈不在算法，在 Vulkan 后端的三个工程缺陷（§5） |
| **支持非 RAW 图片吗** | **支持，而且代码已经写好了一大半。** macOS 已支持 `heic/jpg/jpeg/png/tif/tiff/exr`；C++ 侧 `engine/host/image_io.cpp` 有完整的 JPEG/PNG/TIFF + ICC + 16 bit + EXIF 方向解码器。**只缺 HEIF/HEIC**（需用 Android 系统 `ImageDecoder`），以及 `preview_host` 没接线。详见 §4.4 |
| **能直接拍 RAW 吗** | **能。** Camera2 的 `ImageFormat.RAW_SENSOR` + `DngCreator` 出标准 DNG，再交给现成的 LibRaw。是可选能力，非所有机型支持，需降级。详见 §4.4.4 |
| **能调用小米 Ultra RAW / 厂商计算摄影吗** | **不能，而且不该调。** Android 无公开接口；第三方最高只能拿 binned 的单帧 RAW（S25 Ultra 200 MP 对第三方只开 12.5 MP）。逆向项目确实存在（`OffLineM9` 把小米 17U 徕卡 M9 逐节点还原了），但需要 ROM + Ghidra + QNN，不可上架。详见 §4.4.6 |
| **现在还剩多少活** | **约 37–56 人日**（不是我最初估的 ~85）。因为 `android/main` 上**已经有一个能跑的编辑器**（S1 探针 + S2 批处理 + S3/S4 JNI + S5 Compose + S6 导出/撤销）。增量集中在三块：拍摄 ≈ 9–14、HEIC 编解码 ≈ 10–15、胶卷模型 ≈ 3–4。详见 §1.5、§6 |
| **拍摄进 v1 吗** | **进。** Figma 已有 C3 拍摄屏。目标是 binned RAW ≈ 12 MP，正好是我们要的像素量。详见 §4.6 |
| **片边 / 日期后背** | **不进 P0。** 引擎在 Vulkan 后端 refused，v1 只做"禁用 + 原因"，移植推到 v1.1（8–12 人日）。详见 §4.5.1 |
| **HEIC 导出** | **做，而且要用 libheif**：10-bit + ICC（`colr` box 里写 profile，不是只写 `nclx`）+ ISO 21496-1 增益图。**不要用系统 `HeifWriter`**（8-bit、无 ICC）。详见 §4.7 |
| 最大的技术风险 | **SSBO 上限**：单 buffer 规格下限 128 MiB = 11.18 MP，而 12 MP 全帧 RGB32F 是 144 MB。**手机优先时差得只有 7%，降采样就能过**（§5.1 ④）；只有要接 24/45 MP 大 RAW 时才必须做分带改造 |
| 最该先做的一件事 | **半天到一天走查 `android/main`**（`engine_jni.cpp` 的线程/生命周期、`EngineClient.kt` 的取消、`ExportWorker.kt` 的前台服务），校准工时；然后立刻起 C3 拍摄 + libheif 两条线 |
| UI 技术栈 | 建议 **Kotlin + Jetpack Compose + JNI**；`windows_UI` 的 Qt/C++ host 层**复用到 JNI 层**，但 QML 界面不复用（§8） |
| 许可证 | 代码 GPL-3.0-or-later、profile CC BY-SA 4.0、LibRaw LGPL-2.1/CDDL、Qt 若采用则 LGPL-3 —— 上架 Google Play 可行，但义务明确（§10） |

---

## 1. 代码盘点：我们到底继承了什么

### 1.1 引擎（`engine/`，`src` 约 17,957 行 + `host` 3,249 行 C++）

这是整个迁移里**唯一真正的资产**，而且它已经不再是 macOS 独占的。

| 层 | 文件 | 状态 |
|---|---|---|
| 可移植核心 | `src/core/**`（18 个 .cpp：光谱、密度曲线、CAM16、打印 LUT、参数 schema） | 纯 C++20，无平台依赖 |
| 管线 | `src/pipeline/pipeline.cpp`（125 KB）、`engine.cpp`（116 KB）、`overscan.cpp`（144 KB） | 与 GPU 后端解耦，靠 `gpu::Gpu` 抽象 |
| **GPU 抽象** | `src/gpu/gpu.hpp`（25 KB） | 5 个动词：`alloc / upload / dispatch / flush / read / texture` |
| Metal 后端 | `src/gpu/metal_gpu.cpp` | macOS 用，Android 不需要 |
| **Vulkan 后端** | `src/gpu/vulkan_gpu.cpp`（52 KB）+ `src/shaders/vulkan/*.comp`（**34 个 kernel**） | **Android 直接用这个** |
| C ABI | `include/spektrafilm/spk_engine.h`（29 个导出函数，367 行） | 稳定、`noexcept`、参数用 JSON |
| RAW 解码 | `src/io/raw_decoder.cpp` + LibRaw 0.22.2（`cmake/LibRaw.cmake`） | **无 Win32 依赖，可直接移植** |
| 导出 | `src/io/image_writer.cpp`（PNG/JPEG/TIFF16）、`host/output.cpp` | 仅 TIFF 的 Win32 事务写在 `#ifdef _WIN32` 里 |
| 桌面宿主 | `src/desktop/preview_host.cpp`（17.5 KB） | **无 Win32、无 Qt**，这是现成的"核心层" |
| 资源 | `engine/resources/` 14.8 MB（81×5nm 光谱常量 9 MB + 28 个 profile） | 打进 APK 即可 |

**关键结论：Vulkan 后端不是"计划中"，是已经跑通的。** `engine/PORT_COVERAGE.md` 记录 14/14 个 CTest 在 lavapipe（Mesa CPU Vulkan）上通过；Windows 侧在 RTX 5070 Ti + MinGW 上被验收过，其输出与 Linux GCC 15.2 基线**逐字节一致**。与 macOS Metal 的整图比对是另一件事，由 `parity_metal_vulkan.py` 承担（见 §7）。

### 1.2 34 个 Vulkan kernel 的可移植性（这是好消息）

我逐个文件扫过 `src/shaders/vulkan/`：

```
#version 450            → 34 个文件，全部 core 语法
atomicAdd               → 0
imageLoad / imageStore  → 0
纹理 / sampler           → 0
subgroup / 共享内存      → 0（workgroupBarrier = 0）
push_constant           → 0
float16_t               → 0
SSBO (std430)           → 全部
local_size_x = 256      → 全部
precise                 → 103 处（可复现性所必需）
constant_id             → 2 处（fma 探针）
```

**这意味着：只要是能跑 Vulkan 1.0 的 Android 设备，这套 kernel 就能跑。** 没有任何"移动 GPU 不支持"的特性 —— 没有 subgroup、没有 16 位存储、没有 image 采样、没有原子操作。这是一个非常理想的移植起点。

### 1.3 UI 层与原型：四个候选（都不是 Android 代码）

| 位置 | 技术 | 规模 | 对 Android 的价值 |
|---|---|---|---|
| `modern_UI/Spektrafilm` | Swift + SwiftUI/AppKit | 170 文件 / 51,597 行 | **不可复用**，但它是**行为规格**：交互、图层语义、表面一致性规则都在这里 |
| `windows_UI` | Qt 6 Quick + C++ | `Main.qml` 550 行 + 9 个 C++ 文件（约 114 KB） | **C++ host 高度可复用**（Qt 只在外层） |
| `desktop` | Tauri + React/TS | 约 6,000 行 TS | 其 `HOST-PROTOCOL.md` 的 JSON 协议对 Android 有参考价值，代码不复用 |
| **`docs/SpektraLab-Android-原型设计说明.html`**（2026-10-31 新增，非本会话产出） | 原型设计说明书（99 KB HTML / 2.9 MB PDF） | 13 节 + 三档页面清单 | **这是 Android UI 的权威输入**。§11.1 独立列出了与本方案 §8 相同的三条工程路线，结论也一致（Qt 只适合做原型）。**本方案 §8 与 §14 必须服从它，且已知它与引擎能力有两处冲突（见 §5.5）** |

### 1.4 分支策略（必须先做对）

```
origin/main          ← macOS 产品
origin/Windows_Linux ← 领先 52 个提交，真正的跨平台线（Vulkan + LibRaw + Qt + 新的 parity harness）
```

`README.md` §138 明写："Windows and Linux work goes to the `Windows_Linux` branch, never to `main`."

**Android 分支建议从 `origin/Windows_Linux` 切出**，命名 `android`。理由：`Windows_Linux` 上有 `engine/tests/parity_metal_vulkan.py`（470 行的 Metal↔Vulkan 整图比对工具）、`raw_threads.cpp`、`resource_paths.cpp` —— 这些正是 Android 要用的验收工具。

### 1.5 你已经有 Android 工程了：`android/main`

**来源核实（2026-10-31）**：上游 `JamesQiu2005/SpektraLab` **没有任何 android 分支**；`android/main` 上所有提交的作者都是 `lyizhuo`，带阶段前缀 `[S2] / [S5] / [S6]`。所以这是**你自己的工程**，不是上游带来的。

**结论：留着，不要重做。** 它的结构判断是对的，而且比"从零写"好：

```
android/
  native/    S1 设备可行性探针（独立 CMake，不走 Gradle）
  engine/    S2/S3/S4 Gradle library module，产出 libspektralab.so + JNI
  app/       S5/S6 Compose 应用
  cmake/     host_io.cmake：两边都链接的 engine/host 单元
  testdata/  跨平台 fixture
  DEVICE-BASELINE.md  S1/S7 的实测数字，逐设备
  TESTING.md  逐阶段测试配方
```

它做对了四件我们本来要建议的事：

1. **上游引擎原样消费** —— `add_subdirectory(<repo>/engine)`，不改 `engine/**`。这是"上游合并不痛苦"的**根本解法**，比任何分支技巧都有效。
2. **需要改上游的部分单独走分支** —— `engine/vulkan-batching`、`engine/glslc-support` 这类 `engine/<slug>` 分支，而不是混进 android 提交。
3. **真机 CTest** —— 把上游 14 个 `spk_*` 测试交叉编译推到设备上跑，`spk_vulkan_*` 对 float64 参考值比对。这是最硬的正确性证据。
4. **两个容易忘的 Android 细节已经处理**：`-Wl,-z,max-page-size=16384`（Android 15+ 16 KB 页）、`glslangValidator` 是宿主工具所以要显式传 `SPEKTRALAB_GLSLANG`。

**但——按今天确认的结论，它有三处需要改**（不是重做，是补）：

| 差距 | 证据 | 处理 |
|---|---|---|
| **没有拍摄** | `android/app/.../ui/` 只有 Editor / Crop / Export / FilmStrip / Histogram，没有 camera 包 | 新增 C3 拍摄（§4.5、§4.6），5–8 人日 |
| **没有 HEIC 输出** | `app/build.gradle.kts` + `engine/` 无 HEIF 依赖；导出只有 PNG/TIFF/JPEG 路径 | 新增 libheif 编码器（§4.7） |
| **没有片边/日期/场景置位的禁用态** | 原型有这三个面板，Vulkan 后端 refused | UI 层做"禁用 + 原因"（§4.5 冲突表） |

一句话：**这个 android/ 是"编辑器 + 导入"的完整实现，缺的正好是今天我们确认要加的那块（拍摄 + HEIC）**。

---

## 2. 迁移的整体形状

一句话：**这不是"把 App 移植到 Android"，而是"给已有的跨平台引擎补一个 Android 宿主"。**

```
┌──────────────────────────────────────────────────────────┐
│  SpektraLab Android (Kotlin + Compose)                    │  ← 全新写（25–40 人日）
│  相册 / 编辑器 / 导出 / 设置                               │
└───────────────────────┬──────────────────────────────────┘
                        │ JNI（薄门面，只包 spk_* 29 个函数）  ← 新写（4–6 人日）
┌───────────────────────▼──────────────────────────────────┐
│  libspektralab-core.so  (arm64-v8a)                       │
│  ┌────────────────────────────────────────────────────┐  │
│  │ desktop/preview_host.cpp  会话/帧/并发（已存在）      │  │  ← 复用，几乎不改
│  │ io/raw_decoder.cpp        LibRaw 解码（已存在）       │  │  ← 复用 + 交叉编译
│  │ pipeline/engine.cpp       C ABI 实现（已存在）        │  │  ← 复用
│  │ core/** + pipeline/**     33 个渲染节点（已存在）     │  │  ← 复用
│  │ gpu/vulkan_gpu.cpp        Vulkan 后端（已存在）       │  │  ← 复用 + 3 项改造（§5）
│  └────────────────────────────────────────────────────┘  │
│  assets/engine/**  14.8 MB 资源 + 34 个 .spv              │
└──────────────────────────────────────────────────────────┘
```

**复用的边界**：`engine/src/{core,pipeline,io,gpu,desktop}` + `engine/include` 全部复用。**新写的只有**：CMake 的 NDK toolchain、JNI 门面、Android 平台的输入输出（照片选择、MediaStore、色彩空间）、UI。

这个形状的好处：macOS、Windows、Android 三端共享同一份渲染代码和同一套验收基准。任何一端配色跑偏，其它两端能立刻发现。

---

## 3. 关于 MNN：为什么它不是这里该用的东西

先澄清两个概念，因为提问里把它们合在了一起：

- **Vulkan** 是图形/计算 API。这个引擎用的是 **Vulkan Compute**（`vulkan_gpu.cpp` + 34 个 `.comp`），不是"Vulkan 推理"。
- **MNN / NCNN / TFLite** 是**神经网络推理框架**。它们的输入是"一张算子图 + 一堆权重"，价值在于把 conv / gemm / 激活函数调度得又快又省内存。

### 3.1 这个引擎里没有神经网络

我做了实测，不是凭印象：

```
$ grep -r "onnx|tflite|neural|inference|weights" engine/src engine/include
→ 1 处命中，是一个英文注释里的 "inference" 一词（pipeline.cpp:591）
```

33 个渲染节点全是**解析式物理计算**：

| 节点 | 数学本质 | MNN 能优化吗 |
|---|---|---|
| 光谱上采样 / 81×5nm 卷积 | 固定矩阵乘 + 非线性 | 部分（但已是一个 matmul3 kernel） |
| 染料密度曲线 / log10 guarded | 逐元素查表 + 对数 | 否（无算子可映射） |
| 光晕 / DIR 扩散 | **双调和 IIR 滤波**（double-float 拆分精度） | 否 |
| 颗粒 | **泊松抽样的确定性 RNG** + 染料云高斯模糊 | 否 |
| 打印 LUT | 3D LUT 三线性插值 | 否 |
| CAM16 / gamut compress | 色貌模型，逐像素分支 | 否 |

**没有一层是 conv/gemm。** 把这张图交给 MNN，MNN 的每个"优化"都无处施展，你只能把这些计算写成 MNN 的 custom op —— 那就是"自己写 kernel + 额外承担 MNN 的调度与内存开销"，净亏损。

### 3.2 如果硬要套，会发生什么

- MNN 的 Vulkan 后端为 `conv2d / gemm` 做了 tile 划分和权重常驻。本管线没有权重可常驻。
- 为了"提升效率"而把 34 个已被逐字节验证过的 kernel 重写成 MNN custom op，等于**主动放弃 `parity_metal_vulkan.py` 的 1 个码值验收门槛** —— 这个门槛是当前最值钱的资产之一。
- MNN 自带内存池，会和 `gpu::Gpu` 的引用计数池（`BufferRef` + `PoolAudit`，解决了 24 MP 从 6.4 s 降到 0.8 s、3.2 GB 降到几百 MB 的那次修复）打架。

### 3.3 那"效率"问题到底在哪 —— 三个已定位的工程缺陷

性能瓶颈**不在框架选择**，在 Vulkan 后端。我在代码里读到了三处明确的、可量化的欠账：

| # | 缺陷 | 证据 | 影响 |
|---|---|---|---|
| 1 | **每次 dispatch 同步提交** | `vulkan_gpu.cpp`：`flush() { return true; }  // dispatch is synchronous for this milestone`，且文件头写 "Command batching and native texture export are subsequent steps" | 一次渲染 30+ 次 `vkQueueSubmit` + `waitForFences`。桌面端每次约 0.1–1 ms，手机端更贵；Metal 侧是"整帧一次提交" |
| 2 | **显示走 CPU 回读** | `vulkan_gpu.cpp::texture()` 里 `malloc` + `read()` 全量拷回 | 45 MP RGBA16 = 363 MB 的内存拷贝 + 一次 GPU→CPU 同步 |
| 3 | **全帧节点无法分带** | `kFilmStages` / `kPrintStages` 里 `film_grain`、`print_glare`、`film_boost`、`film_overscan` 是 `StageClass::Whole` | 决定 §5.1 的内存天花板 |

**所以正确的动作是修这三件事，而不是换框架。** 这三项也正好是 Android 端最需要的优化。

### 3.4 MNN 未来唯一可能有位置的地方

如果产品以后要加**神经网络类特性**（AI 去噪、AI 超分、神经网络去马赛克），那时可以把 MNN 作为**并列的独立模块**接进来：MNN 跑那块小网络，主管线仍然走 Vulkan。两者不冲突，但那是新功能，不是迁移。现在引入只有成本。

---

## 4. 目标架构（Android 侧）

### 4.1 进程与线程模型

沿用引擎既有的约定（`preview_host.hpp` 已写明，Windows 也这么做）：

- **引擎单实例、单串行 worker 线程**。`spk_*` 的调用全部投递到这一条线程，UI 线程永不直接调引擎。
- **帧是不可变的**：每次渲染产出一个 `FramePtr`（`shared_ptr<const Frame>`），UI 只拿快照，永不持有可变的引擎状态。Windows 侧已按这个模式验证过（"The UI may concurrently read shared const Frames, which stay valid after this host and its engine are destroyed"）。
- **编辑合并**：滑块拖动期间只保留最新一组参数，被取代的渲染结果直接丢弃。

Android 侧的对应物：

```kotlin
// 单线程串行执行器 + 结构化的取消
val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
```

- **长渲染（full tier）**：走 `Foreground Service`（`dataSync` 类型），带通知进度。手机上一次 45 MP 全渲染可能 5–15 s，不能挂在 Activity 生命周期上。
- **热感知**：官方 API 是 `PowerManager.addThermalStatusChangedListener` + `getThermalHeadroom(int forecastSeconds)`（后者能**提前**给出余量，更适合"在降频前主动降级"）。进入 `THERMAL_STATUS_SEVERE` 时自动降级到 preview 档并提示。`dwaipayanray95/vesper-cine` 的做法可参考：按热预报逐级降低处理强度，而不是等降频发生。

### 4.2 JNI 门面的边界

不要暴露 C++ 类型。JNI 层就是 `spk_engine.h` 的 Kotlin 镜像：

```kotlin
external fun engineCreate(resourcesDir: String): Long          // -> spk_engine*
external fun engineDestroy(h: Long)
external fun open(h: Long, pixels: FloatArray, w: Int, h: Int): Long   // -> spk_session*
external fun setParams(session: Long, deltaJson: String): Int
external fun render(session: Long, tier: String, out: RenderResult): Int
external fun reprint(session: Long, tier: String, out: RenderResult): Int
external fun cancel(session: Long, progressId: String)
external fun capabilities(h: Long): String
// ...
```

**注意**：`spk_image` 是 `float32` 交错 RGB(A)。一张 12 MP 帧 = 144 MB 的 `FloatArray`。**不要**在 JNI 边界用 `FloatArray` 拷两次（Java 堆一次 + native 一次），要么用 `direct ByteBuffer`，要么在 native 侧完成"解码→渲染"整段，Java 只传文件路径。**推荐后者**：`openRaw(path)` 已在 `preview_host` 里做好，直接复用。

### 4.3 资源打包

`spk_engine_create(resources_dir, device)` 要的是**文件系统路径**（`resources_dir/vulkan/*.spv`）。Android 的 `assets/` 不是文件系统路径。两个选择：

| 方案 | 做法 | 评价 |
|---|---|---|
| **A. 首次启动解压**（推荐） | 把 `assets/engine/**` 用 `AssetManager` 拷到 `context.filesDir/engine/`，写一个版本戳文件，版本变了才重解压 | 简单、零 C++ 改动；代价是首启动多 14.8 MB 的 IO（约 0.3–0.8 s） |
| B. 改引擎读 `AAssetManager` | 给 `core/file_path.hpp` 加一层 `ResourceReader` 抽象 | 干净，但要改 4 处 `ifstream` 站点 + 全部资源加载路径，风险大于收益 |

**选 A。** 顺带把 `engine/resources_product/` 的黑白胶片 profile 一起带上（`build.sh bundle` 在 macOS 上做的事，在 Android 上由 CMake 的 custom target 完成）。

### 4.4 输入层：拍摄 + 导入（手机的真实用法）

手机端的输入不是"一堆相机 RAW"，而是三类：**应用内拍摄 > 相册普通图 > 相册 RAW**。好消息是**前两类所需代码的一大半已经在这个仓库里**。

#### 4.4.1 现状：项目已经支持非 RAW 图片（本次调研最重要的发现）

**macOS 产品侧（已发布，`modern_UI/.../Import/ImageDecoder.swift`）**

```swift
static let rawExtensions: Set<String> =
    ["nef","arw","dng","cr2","cr3","raf","rw2","orf","pef","srw","3fr","iiq"]
static let flatExtensions: Set<String> = ["tif","tiff","png","jpg","jpeg","heic","exr"]
static var openable: Set<String> { rawExtensions.union(flatExtensions) }
```

`decodeFlat()`（`:266`）的做法：`CIImage(contentsOf:)` → 应用 EXIF 方向 → **匹配到线性 ProPhoto** → 交给引擎。`isRAW: Bool` 一路传到 UI（flat 文件没有镜头校正、没有 as-shot 白平衡）。注释写得直白：

> "A flat file is already a rendering: there is no second look to take."
> "For a flat file the two [linear / display] are one image."

**C++ 侧（可移植，`engine/host/image_io.hpp`）—— 这份才是 Android 要用的**

> what the host reads: RAW (LibRaw), TIFF, JPEG, PNG
> - RAW → LibRaw，线性 ProPhoto
> - 带内嵌 ICC 的 TIFF/JPEG/PNG → **关掉 profile 的曲线，用它的 D50 色料矩阵转到 XYZ，再转线性 ProPhoto**（"what ColorSync/Core Image do for the same file on macOS"）
> - 无 profile 的 JPEG/PNG/8 bit TIFF → sRGB（"Core Image assumes the same"）
> - 无 profile 的 16 bit / float TIFF → 线性 ProPhoto（"the app's own linear export"）

而且不是只写在注释里：`image_io.cpp` 里有完整的 **ICC 解析器**（`parse_icc`、`Trc::decode`、matrix/TRC 两类曲线、D50 色料）、**16 bit PNG**（`stbi_is_16_bit` / `stbi_load_16_from_memory`）、**EXIF 方向**（JPEG APP1 / PNG eXIf / TIFF IFD0 → `apply_orientation`）。

**两个缺口：**

1. **C++ 侧完全没有 HEIF/HEIC/AVIF/WebP**（`FileKind` 只有 `Raw/Tiff/Jpeg/Png`；`grep heif|avif|webp` 命中 0）。macOS 是 Core Image 免费给的，Windows/Android 没有。
2. **没接线**：`windows_UI` 用的 `preview_host.cpp` 只调 `io::decode_raw_*`（`:252-254`），所以 **Windows 界面目前是纯 RAW**；完整解码器只有 CLI host（`engine/host/main.cpp`）在用。Android 要做的就是**把这根线接上**。

#### 4.4.2 Android 的解码矩阵

| 输入 | 谁解码 | 交给引擎的形状 |
|---|---|---|
| 应用内拍摄的 RAW（`RAW_SENSOR`） | Camera2 → `DngCreator` → DNG → **LibRaw** | linear ProPhoto float32 |
| 相册导入 RAW（DNG/CR2/NEF/ARW…） | **LibRaw**（复用 `raw_decoder.cpp`，已就绪） | 同上 |
| 相册导入 HEIC/HEIF/AVIF/WebP | **系统 `ImageDecoder`**（API 28+）→ `Bitmap` | 需自己转 linear ProPhoto |
| 相册导入 JPEG/PNG/TIFF | **复用 `engine/host/image_io.cpp`**（含 ICC、16 bit、方向） | 已经是 linear ProPhoto |

**颜色约定不能变**：引擎默认 `io.input_color_space = "ProPhoto RGB"`、`io.input_cctf_decoding = false`（`params.hpp:408-409`），macOS 从来没改过这两个值。**宿主必须把一切输入交付成"线性 ProPhoto 的 float32"**，引擎本身不做这个转换。

**HEIC 怎么转**（Android 没有 ProPhoto 色彩空间，`ColorSpace.Named` 里没有 ROMM）：

```
Bitmap.getColorSpace()
  → getPrimaries() + getTransferFunction()
  → 构造 RgbSpace/Trc（复用 image_io.cpp 的结构，只是输入从 ICC 换成 ColorSpace 描述）
  → 反 TRC 得线性 → 源 RGB→XYZ(D50, Bradford) → XYZ→线性 ProPhoto
```

**不需要新写颜色科学**，只需给 `image_io.cpp` 的 `RgbSpace` 增加一个"从 `ColorSpace` 描述构造"的入口。这是 Android 上唯一真正新增的解码代码，估 4–6 人日。

> **必须避免的坑**：不要 `Bitmap.copy(ARGB_8888)` 再当 sRGB 处理。10 bit HEIF 的 HDR 信息就在 `ColorSpace` 里，退成 8 bit sRGB 就永久丢失 —— 而 `AGENTS.md` trap 11 恰好记录过这个仓库"所有测试都喂线性输入、display-encoded 输入从未被覆盖"的历史事故。

#### 4.4.3 忠诚度的诚实边界（产品决策，不是技术问题）

支持 JPEG/HEIC **能跑，但不如 RAW 准**。`AGENTS.md` trap 12 已经写清楚：

- **外部解码不可逆**：相机 profile 无法从导出的 TIFF/HEIC 恢复。两个开发器给出两个场景估计，因此**得到两种不同的胶片效果**。
- **外部 RAW 开发器里的曝光编辑不是增益**：它后面还有色调曲线时，实测 `+1 EV` 被写成中位 ×1.57、跨色调 1.8–2.1× 的扩散。"Fix the decode as part of the product contract; do not attempt an adaptation layer."

**建议把定位写进产品**：

| 输入 | 定位 | 期望 |
|---|---|---|
| 应用内拍摄 RAW | **主路径**，传感器线性数据 | 与 macOS 产品同源，胶片模型拿到真实响应 |
| 相册 RAW | 次路径 | 同源，但受 `dcraw` 通用矩阵影响（见 4.4.4） |
| 相册 HEIC/JPEG | **便捷路径** | 看上去对；厂商标定的色调曲线已烘焙，不可追回，UI 上应说明 |

#### 4.4.4 Android 拍摄 RAW 的具体做法

> ⚠️ 本小节的 Android API 细节来自知识而非实时抓取（本会话 web 检索不可用），**能力边界与版本号需在目标机型上核实一次**。仓库相关事实（LibRaw / DNG / 颜色约定）是核对过代码的。

**Camera2 路径（推荐，控制力最强）**

1. 能力检查：`CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES` 必须含 `RAW`。**这是可选能力**，多数旗舰有，中低端常缺 —— 必须有降级路径。
2. 输出：把 `ImageFormat.RAW_SENSOR` 加进 `CaptureRequest` 的输出 Surface。它是 **16 bit Bayer 单通道**，不是能直接看的图。
3. 元数据：`DngCreator(cameraCharacteristics, captureResult)` 把方向、色彩矩阵、白平衡、黑电平、曝光写进 DNG。它需要 `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT`、`SENSOR_INFO_WHITE_LEVEL`、`SENSOR_BLACK_LEVEL_PATTERN`、`SENSOR_NEUTRAL_COLOR_POINT`、`COLOR_CORRECTION_TRANSFORM` 等（组装由 `DngCreator` 负责，但**必须传对 `CaptureResult`**）。
4. 落盘：`DngCreator.writeImage(OutputStream|FileDescriptor, Image)`（API 21+）→ 标准 DNG。
5. 进引擎：**DNG 交给 LibRaw**，复用现成的 `raw_decoder.cpp`。这正是"拍摄 RAW 在 Android 上几乎不需要新 RAW 解码代码"的原因 —— 只是把 `RAW_SENSOR` 变成了 LibRaw 认识的 DNG。

**CameraX 路径（代码更少，但要碰抽象泄漏）**

已核实的 `ImageCapture` 输出格式（来自官方 reference）：

```
OUTPUT_FORMAT_JPEG          = 0
OUTPUT_FORMAT_JPEG_ULTRA_HDR = 1   // Ultra HDR 增益图 JPEG
OUTPUT_FORMAT_RAW           = 2   // "Captures raw images in the RAW_SENSOR image format."
OUTPUT_FORMAT_RAW_JPEG      = 3   // "Captures raw images in the RAW_SENSOR and JPEG image formats."
```

`setBufferFormat(ImageCapture.OUTPUT_FORMAT_RAW)` 即可，`RAW_JPEG` 还能一次拿到 RAW+JPEG。但实践中 RAW 仍要靠 `DngCreator` 补元数据，且 CameraX 对 RAW 的支持面窄于 Camera2。**建议：预览用 CameraX，RAW 采集单独开一条 Camera2**，两者不冲突。

**必须预期的四个现实问题**

1. **Bayer 必须去马赛克**。`RAW_SENSOR` 是未处理数据：黑电平、镜头阴影、白平衡、色彩矩阵全都没做。走 DNG → LibRaw 就都有了，但代价是**多一次编解码**（12 MP Bayer ≈ 24 MB → DNG → LibRaw 解码），约 +0.5–2 s。
2. **LibRaw 用的是 `dcraw` 通用矩阵，Apple 用自家 RAW 引擎**。所以**同一张 DNG 在 macOS 与 Android 上会出不同颜色** —— 这不是 bug，是仓库已记录的既有分歧（`ImageDecoder.swift` 头注释）。对"手机拍、手机显"的产品，这会比桌面端更显眼。**建议 P2 就定：接受它，还是给 Top-N 机型做矩阵标定。**
3. **手机 RAW 质量参差**。部分机型 `RAW_SENSOR` 没有镜头阴影校正，直接出图有暗角与偏色。这是机型白名单问题。
4. **不是所有机型都能拍 RAW**。降级链：RAW → 不可用则系统 JPEG/HEIC（并提示"便捷模式"）。

#### 4.4.5 取景器：完整管线跑不了 30 fps，但"简化预览"业界已经做到了

33 个节点的完整管线在手机上单帧就是秒级（macOS M3 Max 实测 live 1600 px 首次 0.13 s，`ARCHITECTURE.md` §8.7；手机 GPU 慢一个数量级）。**完整管线做取景器不成立，但这不是死局** —— 三个开源项目给出了三种已验证的做法：

| 做法 | 项目 | 代价 |
|---|---|---|
| **对 RAW 跑一条简化管线** | `f0enix/motioncam`（85★）：用 GPU 从 RAW 生成实时预览，"a simplified pipeline to produce an accurate representation of what the final image will look like" | 需要自己写一条轻量管线（降噪+去马赛克+色调映射），与正式管线分开维护 |
| **原生 `SurfaceView` + 自己的 GPU 管线** | `dwaipayanray95/vesper-cine`：Pixel 10 上 1080p 每帧 **29–32 ms**，含对齐+降噪 | 性能预算极紧，需要"性能护栏"逐级降级 |
| **套系统处理后视频流的近似 LUT** | `reki-reki/Silverpoint`：README 明说 "The viewfinder is approximate. It applies the look to the phone's processed video stream; the saved photo is developed from RAW." | 最省，但取景器与成片不一致（可接受，业界通例） |

**v1 建议**：先用 Silverpoint 那种最省的做法（取景器套近似 LUT 或干脆不套），成片才走完整管线：

```
取景器（系统预览 ± 近似 LUT）
  → 快门 → 拍 RAW/HEIC → 1–3 s 处理（带进度）→ 显示成片 → 可再编辑
```

vNext 再考虑"对 RAW 跑的简化管线"。无论走哪条，**把"胶片外观"烘焙成 3D LUT 都是绕不开的一步** —— 用 `spk_lut3d_trilinear` 做实时近似。引擎已有这条路径的雏形（`spk_preview_stock_lut`：live 档 2.0 ms，对比同档 reprint 9 ms），但它现在是**相纸 LUT**、不是"胶片外观 LUT"，需要新增一个烘焙出口。**这是产品级决策，不是一个 patch。**

> 一个有意思的旁证：下文 §4.4.6 的 `OffLineM9` 逆向后发现，**小米徕卡滤镜本身就是一个 17³ 的 3D LUT**（`LeicaFilter`："17³ LUT（lux×CCT 插值）+ CvStyle 暗角"）。厂商也走这条路。

#### 4.4.6 厂商计算摄影（小米 Ultra RAW / 徕卡模式）：**调不到，而且不该调**

这是本次调研的第二个结论。结论先行：**Android 没有任何公开接口能让第三方 App 调用厂商的计算摄影管线**（多帧合成、AI 降噪、徕卡风格）。你只能拿到 `RAW_SENSOR` —— 单帧、Bayer、而且通常是像素合并后的。

**证据一：像素合并是硬边界**

`reki-reki/Silverpoint`（三星 S25 Ultra 的第三方 RAW 相机，与本项目形态最接近）的 README 原文：

> **RAW is capped at 12.5 MP.** The S25 Ultra exposes no maximum-resolution RAW mode to apps, so Silverpoint shoots the 200 MP sensor's binned 12.5 MP RAW, which also keeps noise low.

也就是说：系统相机能出 200 MP，第三方 App 只能拿 12.5 MP 的 binned RAW。**厂商把"满像素 + 计算摄影"这条路对第三方关死了。**

**证据二：连你能拿到的那个 DNG，也已经是"合成过"的**

`benbaobaoshigemi/OffLineM9`（小米 17 Ultra「徕卡一瞬 · M9」的离线 RAW 复刻，40★）在其核对表里明确写：

| 原版节点 | 离线实现 | 依据 / 取舍 |
|---|---|---|
| Anchor / MFNR | **不做** | **必要取舍：DNG 已是相机合成后的单帧** |
| AllinOne（libmialgo_aisp，AI 降噪+BLC/LSC/WB） | rawpy 线性解马赛克 | **必要取舍：AI 降噪模型针对 17U 传感器；DNG 已含 BLC/LSC** |

它把小米的管线逐节点还原了：`AEC(libmiaec + mi_tuning)` → `AWB(libawbcore)` → `MFNR` → `AllinOne(libmialgo_aisp)` → `LTM(ltm21)` → `TMC202(GTM)` → `CC(cc15)` → `2D LUT(tdl13)` → `gamma152` → `CV(cv122)` → `StyleTrans(AI 风格网络)` → `LeicaFilter(17³ LUT)`。

**但它需要：ROM 解包 + Ghidra + QAIRT/QNN HTP 模拟器 + 从 ROM 上下文二进制还原的 PyTorch 权重。仓库本身是私有的。** 这是研究工具，不是能装进 APK 的东西。

**唯一的官方"半开"口子：Camera2 vendor tags**

`CameraCharacteristics.getAvailableCaptureRequestKeys()` / `getAvailableSessionKeys()`（API 28）会返回 OEM 私有参数对应的 `Key<?>`。理论上厂商可以通过 vendor tag 暴露私有能力。但：

- 无文档、逐机型、逐 ROM 版本；
- 厂商**从不**把 "Ultra RAW 模式" 暴露成 vendor tag —— 那是闭源相机 App 与私有 HAL 之间的事；
- 实际用它的项目（4K120 Unlocker、`ColdP/HyperChanger` 等 LSPosed 模块）都在 root / Xposed 层面，**不适合上架**。

**GitHub 上的逆向项目，按可商用性分三类**

| 类型 | 代表 | 能不能进产品 |
|---|---|---|
| **A. 自己重建管线** | `f0enix/motioncam`（85★，多帧对齐+光学流融合）、`reki-reki/Silverpoint`（6 帧对齐平均）、`dwaipayanray95/vesper-cine`（RAW10 → Vulkan → 10-bit HEVC）、`Kp4103/lumenraw-camera`（HDR+ 风格，Redmi 13C） | ✅ **这是正路**，但工作量是自己写一套计算摄影 |
| **B. 逆向厂商算法做离线复刻** | `benbaobaoshigemi/OffLineM9` | ❌ 需 ROM/Ghidra/QNN/私有权重，且法律与合规风险高 |
| **C. 改/解锁原厂相机** | `ColdP/HyperChanger`(318★)、`wuchuzuoyou/MiCamOS1Revive-aurora`、各种 `Xiaomi14/17-4K120-Unlocker` | ❌ 需 root / LSPosed |

**对 SpektraLab 的判断：不该调，原因有两个**

1. **技术原因**：胶片模型需要 **sensor response（场景线性）**。厂商的计算摄影 DNG 里已经含 MFNR、AI 降噪、BLC/LSC、以及一整条风格化管线（含 AI 风格网络）。把它喂进胶片模型，等于**在别人已调好的图上再调一次** —— 这正是 `AGENTS.md` trap 12 说的"外部解码不可逆，Fix the decode as part of the product contract; do not attempt an adaptation layer."
2. **产品原因**：SpektraLab 的卖点是"物理胶片过程"，不是"滤镜"。输入一旦是厂商调好的图，产品的物理叙事就塌了。这与仓库的核心原则**"不要为了修渲染问题污染材料模型"**同构。

**唯一合法的"蹭厂商效果"路径**：让用户在系统相机里拍（用 Ultra RAW / 徕卡模式），再把 DNG 导入 App。这是**消费输出**，不是**调用接口**。对应 §4.4.2 表的第三条输入通道，且必须明示为"便捷模式，颜色不可完全复现"。

**正确的技术路径**：`RAW_SENSOR`（binned 12 或 12.5 MP）→（可选）自己的多帧对齐合成 → 自己的胶片模型。MotionCam 与 Silverpoint 都证明了这条路在 Android 上可行，代价是自己写一套对齐/融合。

---

### 4.5 原型核对：Figma「FilmCam」11 屏 vs 引擎能力

**来源**：`https://www.figma.com/design/X11f1l3ZLxPUwE9nC3Pe6z/FilmCam`，version `2416483502469442079`，lastModified 2026-10-31 13:24。经 REST API 取到 1 个 section、11 个 `412×915` 屏、全部文字节点。

| 屏 | 名称 | 与引擎的关系 |
|---|---|---|
| C1 | 首页 · 胶卷库 | 纯 UI；胶卷是新产品模型（见下） |
| C2 | 导入处理 | 对应 §4.4.2 的解码矩阵 |
| **C3** | **相机拍摄** | **新增主路径**，§4.6 |
| B2 | 相册选图 | Photo Picker；"最多 24 张""保持拍摄顺序" |
| A1 | 编辑器主屏 | 引擎 `spk_render` + 顶栏 `full · 6048×4024` 角标（对应 `Session.canvasBadges`） |
| A2 | 胶片与印相 | 胶片/相纸/放大机 OK；**片边、日期 = refused** |
| A3 | 参数 · 显影前 | 色温/色调/胶片曝光/画幅/颗粒/光晕 OK；**场景宽容度、场景置位 = refused（仅分析可用）** |
| A4 | 参数 · 显影后 | 白平衡/曝光/对比/亮度/饱和度/高光/曲线/色彩平衡 —— 这是**应用层 L2**，引擎不参与，Android 侧自己画 |
| A5 | 裁剪 | 后渲染几何，不重跑乳剂 `; 已注明"片边开启时比例由片边锁定" |
| A6 | 导出 | 见 §4.7；**原型没有 HEIC 配方，需加** |
| B1 | 多选与同步 | 复用 `PreviewController` 式的同步语义；"没有批量撤销"要如实呈现 |

#### 4.5.1 三处"原型画了、引擎拒绝"的冲突

`engine/PORT_COVERAGE.md` §2 与 `engine.cpp` 的 `backend.unsupported_features` 明确列出五个 Apple-only 特性。原型触到了其中三个：

| 原型位置 | 对应 wire 字段 | 非 Apple 后端 | 必须怎么做 |
|---|---|---|---|
| A2「片边」页签 | `overscan_*`（`film_render.overscan.*`） | **refused**（`SPK_ERR_USER`） | 页签保留但置灰；点开显示原因一行字（原型 §8.3 已有此规范） |
| A2「日期」页签 | `date_imprint_*` | **refused** | 同上 |
| A3「场景宽容度 / 场景置位」 | `camera.scene_latitude.*` | **分析可用，应用 refused** | 「测光」按钮可以跑（`spk_scene_latitude` 是 analysis）；**回拉滑条与"应用置位"必须禁用**，并说明"当前平台只支持测量" |

> 这是本方案与原型之间**唯一需要设计介入**的地方。好消息是原型的 A3 已经画了"正在测光… 完成后可用"和"未就绪"两种状态 —— 把"完成后可用"改成"完成后可测量，本平台不支持应用"，UI 结构不用动。
>
> 若日后决定移植（`PORT_PLAN.md` 估 12–18 人日），这五处会一起解开，届时只改禁用态，不改布局。

#### 4.5.2 原型的两个"新产品模型"，工程要接住

1. **胶卷（Roll）** —— C1 有「我的胶卷 Portra 160 · 17 张 · 今天」带进度条，C3 有 `135 · 36 EXP` 与 `17 / 36` 计数。这不是现有 macOS 应用的概念（那边是"文件夹 + 胶片条"）。需要新的持久化实体：
   ```kotlin
   data class Roll(id, filmStockId, paperStockId, capacity = 36,
                   createdAt, frameIds: List<String>)   // = 一次"装卷"
   ```
   存储位置同 sidecar（`filesDir/rolls/<id>.json`），不进照片目录。

2. **拍摄即入卷** —— C3 快门 → 生成一张 DNG/HEIC → **自动加入当前胶卷**（C3 缩略图带 `17` 角标）。所以拍摄路径要复用编辑器的 import 管线，不能另起一套。

#### 4.5.3 原型没画、但 v1 必须有的状态

C3 只有"正常"一态。按 `AGENTS.md` 的"禁用必须可点出原因"规范，至少要补五种：

```
无 RAW 能力      → 降级到系统 JPEG/HEIC，角标"便捷模式"
相机权限被拒      → 引导 + 不阻塞（可只走相册）
DNG 写入中        → 快门禁用，进度
开发中            → "冲洗中 1.2s"，可取消（spk_cancel 已有）
热保护            → 降级到 preview 档并提示（getThermalHeadroom）
```

原型 permission 机会不多，但 C3 是唯一会**主动要相机权限**的界面，这一块必须在设计上过一遍。

---

### 4.6 拍摄（C3）的工程落地

按原型：取景器带网格、中心对焦框、左侧 EV 浮盘、底部 `0.7 / 1× / 2×` 镜头切换、`AUTO · ISO 400` 与 `1/125 F4.0` 读数、快门、左下缩略图、右下翻转。

#### 4.6.1 取景器：先给一个诚实的版本

原型取景器里的画面是**已经套上胶片效果**的。这条要正视（§4.4.5）：

| 方案 | 做法 | 代价 | 建议 |
|---|---|---|---|
| **A（v1）** | 系统 CameraX Preview（原生画面）+ 叠一层**近似 3D LUT**（`ColorMatrix`/`RenderEffect` 或 OpenGL 采样） | 需要烘焙一张 LUT；画面与成片不完全一致 | ✅ **v1 就用这个** |
| B | 自己对 RAW 跑简化管线（demosaic + 局部色调 + LUT），SurfaceView + Vulkan | 8–15 人日，MotionCam 级工程量 | vNext |
| C | 完全不做效果，取景器就是原生画面 | 0 成本，但产品感差 | 备选 |

**LUT 烘焙**是 A 的关键：把"胶片 + 相纸 + 当前参数"在这台机器的引擎上渲一遍，得到 `17³` 或 `33³` 的 `.cube`，App 缓存它（参数变了才重烘）。引擎已有 `spk_lut3d_trilinear` 与 `spk_preview_stock_lut` 的整条路径可复用（`spk_preview_stock_lut` 在 live 档 2.0 ms），缺的只是一个"胶片外观"的烘焙出口 —— 估 **4–6 人日**。

#### 4.6.2 镜头切换 `0.7 / 1× / 2×`

这是**多摄**，不是数码变焦。Camera2 侧对应 `LOGICAL_MULTI_CAMERA`：

- 物理相机列表：`CameraCharacteristics.getPhysicalCameraIds()`（API 28+）；
- 每颗物理相机的焦距：`LENS_INFO_AVAILABLE_FOCAL_LENGTHS`，用它算等效倍率；
- 切镜头 = 换 `CameraId`，不是改 `CONTROL_ZOOM_RATIO`（后者在 API 30+ 可做无缝变焦，但会走 ISP 裁切，画质不如换物理头）。

**建议**：`0.7` 映射到 `DEPTH`/超广角那颗，`1×` 映射主摄，`2×` 映射长焦；用**各自的 RAW 能力**分别判断（超广角常常没有 RAW）。降级时该档置灰。

#### 4.6.3 曝光读数 `AUTO · ISO 400` / `1/125 F4.0`

原型显示了真实曝光三元组，说明要暴露手动/半自动。最小实现：

- `CONTROL_AE_MODE_ON` 读 `SENSOR_SENSITIVITY` + `SENSOR_EXPOSURE_TIME` + `LENS_APERTURE`（光圈在手机上固定，直接读 `LENS_INFO_AVAILABLE_APERTURES[0]`）；
- EV 浮盘写 `CONTROL_AE_EXPOSURE_COMPENSATION`，范围取 `CONTROL_AE_COMPENSATION_RANGE`（真机上常见 ±2 或 ±4，不是原型暗示的更宽范围）；
- 手动模式（`MANUAL_SENSOR` 能力）再暴露 ISO/快门。

#### 4.6.4 拍什么格式

按今天确认的"目标 12 MP、binned RAW 也可以"：

```
快门 → 同一次 capture 请求 RAW_SENSOR + JPEG/HEIF 两路输出
        ├─ RAW_SENSOR → DngCreator → .dng  （Linear ProPhoto 的输入，主路径）
        └─ JPEG/HEIF  → 缩略图 + "便捷模式"备份
```

`ImageCapture.OUTPUT_FORMAT_RAW_JPEG`（CameraX）或 Camera2 的 `TEMPLATE_STILL_CAPTURE` 同时挂两个 Surface 都能做到。开 `ON` 参数：`dng` 落 `DCIM/SpektraLab/`，HEIC 落相册。

---

### 4.7 导出：加 HEIC，且把色彩守住

原型的 A6 只有四条配方：`TIFF 16 / JPEG P3 / PNG sRGB / DI Cineon`。按今天的要求要加 HEIC，而"我们是做色彩的 app"意味着**HEIC 不能只是"换个容器"**。

#### 4.7.1 A6 已有的色彩表达（做得很好，保留）

```
文件尺寸 1:1 · 6048 × 4024 · Display P3 · 16-bit
超出目标色域 1.8% · 被映射 0.6% · 被夹限 0.1%
格式 TIFF | 位深 16-bit | 色域 ProPhoto RGB | 长边 原始尺寸 | 位置 Pictures/SpektraLab
```

那个"超出目标色域 / 被映射 / 被夹限"的三段统计是**差异化功能**，HEIC 配方必须同样给出。

#### 4.7.2 HEIC 的规格

| 项 | 怎么做 | 理由 |
|---|---|---|
| 编码器 | **libheif**（LGPL-3，与本项目 GPL-3.0 兼容；★2415；README 明写支持 "HDR images, correct color transform according to embedded color profiles"） | Android 自带的 `HeifWriter` 只有 8-bit YUV/RGBA、无 ICC，做不了色彩 app |
| 位深 | **10-bit**（`heif_chroma_420` + bit depth 10，HEVC Main10） | 8-bit 装不下 ProPhoto→P3 的渐变，会出现带状 |
| 色域 | 写 **ICC profile**（`colr` box，`prof`/`rICC`） | 不是只写 `nclx`；`nclx` 只能表达少数固定色域，ProPhoto 必须走 ICC |
| HDR | **ISO 21496-1 增益图**（SDR 基底 + 增益图元数据），与 Android Ultra HDR / Apple gain map 同一套机制 | 文件在非 HDR 设备上仍然正常显示 |
| 质量 | `heif_encoder_set_quality()` + `preset=slow` | |
| 统计 | 复用现有的 gamut 三段统计，对 HEIC 目标的 P3/BT.2020 各算一遍 | 与 TIFF/JPEG 配方一致 |

**不要**用 `android.media.HeifWriter`：它接受 `YUV_420_888` / `Surface`，8-bit，没有 ICC 写入路径 —— 对色彩 app 是降级。

#### 4.7.3 组件选型（"成熟开源就直接用"）

| 用途 | 选型 | 许可证 | 星数 | 备注 |
|---|---|---|---|---|
| HEIC/HEIF 编解码 | `strukturag/libheif` | LGPL-3 | 2415 | 10/12-bit、ICC、HDR、平铺 |
| HEVC 编码器 | `libx265`（libheif 后端）或 `kvazaar`（更快、更省电） | GPL-2 / BSD | — | 手机端建议 kvazaar 或 x265 `ultrafast` |
| Ultra HDR / 增益图 | `google/libultrahdr` | Apache-2.0 | 349 | JPEG 侧参考实现；HEIC 侧自己拼 ISO 21496-1 元数据 |
| ICC 引擎 | `mm2/Little-CMS` (lcms2) | MIT | 763 | 生成/嵌入 profile；引擎侧已有自己的 ICC 解析器可复用读取 |
| JPEG | `libjpeg-turbo` | BSD | 4460 | 比 Android 自带快 |
| RAW | `LibRaw` | LGPL-2.1/CDDL | 1617 | 已在用 |
| 解码（HEIF/AVIF/WebP 输入） | Android 系统 `ImageDecoder` | 平台 | — | 不引第三方，省包体 |
| 相机 | `CameraX`（预览）+ `Camera2`（RAW 采集） | Apache-2.0 | — | §4.6 |

**包体预算**：libheif + libx265 约 +6–9 MB（arm64），libultrahdr +0.4 MB，lcms2 +0.3 MB。相对现有 14.8 MB 资源可接受；若在意，x265 可用 `kvazaar` 换（+2 MB）。

---

## 5. 四个硬约束（方案的真实难点）

### 5.1 内存：128 MiB 的 SSBO 下限 × 12 字节/像素 —— 这是全案第一风险

引擎的每一张中间帧都是 **tightly packed float32 RGB**（`pipeline/image.hpp`）：

```
12 字节/像素
 1 plane @ 12 MP (4000×3000) = 144.0 MB
 1 plane @ 24 MP             = 288.0 MB
 1 plane @ 45.4 MP (Z7II)    = 545.0 MB
```

Vulkan 规范要求 `maxStorageBufferRange` **至少** 2²⁷ = 134,217,728 字节（128 MiB）。很多移动 GPU 就报这个最小值。

```
128 MiB / 12 B = 11.18 MP  ← 单 buffer 上限对应的像素天花板
```

**也就是说：一张 12 MP 的手机照片（1200 万像素），全帧 RGB32F 是 144 MB，已经超过 128 MiB。** 这不是"大图才有的问题"，这是默认场景就踩线。

而且 `spk_open` 的输入还要再占一份（`spk_image` 是 caller-owned float32，引擎会拷贝一次）：

| 环节 | 12 MP | 45.4 MP |
|---|---:|---:|
| LibRaw 解码（RAW → 16 bit） | ~72 MB | ~272 MB |
| `spk_image` 输入（float32 RGB） | 144 MB | 545 MB |
| 引擎内 source 拷贝 | 144 MB | 545 MB |
| 活动中间帧（2–3 张） | 288–432 MB | 1.1–1.6 GB |
| RGBA16 结果 | 96 MB | 363 MB |
| **峰值合计** | **≈ 0.7–0.9 GB** | **≈ 2.6–3.1 GB** |

手机上应用可用内存通常在 1–2 GB 量级（受 LMK 与 `largeHeap` 影响），45 MP 这条路**现在走不通**，12 MP 勉强、16 MP 悬。

**四条缓解路径，按性价比排序：**

**① 让 whole-frame 阶段变成可分带的（最根本，8–12 人日）**

这是唯一能真正解除上限的办法。当前 `StageClass::Whole` 有四个：`film_boost`、`film_overscan`、`film_grain`、`print_glare`。它们之所以是 Whole，是因为**节点体内含模糊**（注释写得很清楚："Its body holds its own dye-cloud blurs"）。

但模糊是可以带 halo 分带的 —— 引擎里已经有这套机器：

- `Blur::Demand{ carried, halo }`（`pipeline/blur.hpp`）已经能给出"这个模糊需要几行 halo"；
- `run_stages_striped` 已经实现了"band-able 运行段逐带跑 + halo 沿链累加 + 全帧节点物化一次"；
- **`settings.striped` / `strip_rows` / `strip_budget_bytes` 已经在 API 上暴露**（`params.cpp:98-100`），Android 侧现在就能用 `spk_set_params` 打开分带，不用改一行 C++。

需要做的：
1. 把 `grain_realise` 里的染料云模糊改成 `Neighbourhood`（halo ≈ `ceil(3σ)+1`，σ 由 `blur_dye_clouds_um` 算出；`AGENTS.md` trap 9 已记录"模糊需要 ~4 px halo"）；
2. 把 `node_glare` 的模糊包成 `Neighbourhood`。**随机场本身不用改** —— 已确认 `spk_lognormal_field.comp` 用的是 `rng_init(i, stream0, seed)`，即以**全局像素索引 `i`** 播种，天然是"按索引确定性"的，分带不改变任何一个像素的取值；
3. 把 `film_boost` 标注为点态（它看起来是逐像素的高光提升）；
4. 实现 `choose_strip_height` 的预算逻辑（现在 `(void)budget_bytes;` —— 收下了但没用，1–2 人日）。

> **实现时的一个必须遵守的点**：分带后传给 kernel 的 `i` 必须仍是**全帧全局索引**，不能是带内局部索引。否则颗粒与眩光的实现会随 `strip_rows` 改变，第 2 步的验收（"`strips == 1` 与 `strips == N` 逐字节相同"）就不成立。这是这类改造最典型的一处静默错误。

做完全部 4 步后，**任何一个全帧 buffer 都降到 `宽 × strip_rows × 12`**。5504 宽、512 行 → 33.8 MB，稳稳在 128 MiB 内。

**② 分档 + 上限（1–3 人日，立即可做，**且手机优先时它就是正解**）**

Android 上给一个"可渲染最大像素"策略，阈值直接由设备算：

```
max_pixels = maxStorageBufferRange / 12        // 12 B/px
             再留一点余量（SSBO 之外还有对齐与 descriptor 开销）
```

- 设备 `maxStorageBufferRange ≥ 256 MiB` → 阈值约 20–22 MP，12–16 MP 直接全分辨率；
- 设备报 128 MiB（规格下限）→ 阈值约 10–11 MP，**12 MP 帧正好超出**。

超出时**降采样到阈值以内，而不是拒绝**。细节与"为什么这能让 ① 降为条件触发"见 ④。

**③ fp16 存储（5–10 人日，慎用）**

把中间帧存成 half：带宽和容量都减半，Adreno/Mali 的 fp16 算力还是 fp32 的 2×。**但 `AGENTS.md` trap 6 已经实测过这条路有坑**：`1e-10` 的 epsilon 在 fp16 下下溢成 0，`log10(0)` 得 `-inf`；而且"fp16 并不更快（6.9 ms vs 6.8 ms）"。所以**只能对特定 buffer 做**（密度域、显示域），并且必须用 `parity_metal_vulkan.py` 卡 1 码值。作为 P1 的优化项，不作为 P0 的救命方案。

**④ 手机优先时，可能根本不用做 ① —— 一条 3 人日的出路**

这是本次调研改变了结论的地方。如果产品定位确定为"手机拍 + 相册普通图"（不接 45 MP 相机 RAW），那么**唯一需要跨过的是 11.18 MP 这条线**，而不是 45 MP。

```
128 MiB / 12 B = 11.18 MP          ← 单 buffer 上限
12 MP 手机照片 = 144 MB            ← 超出 7.3%
```

差的只有 7%。所以：

- **把"最大可渲染像素"设成按设备算出来的值**，超出时**降采样**而不是拒绝。12 MP → 11.1 MP 是 4% 的线性分辨率损失，肉眼不可见；换来的是**在 128 MiB 档设备上零引擎改动就能出图**。
- 真机上如果 `maxStorageBufferRange ≥ 256 MiB`（旗舰多半如此），连降采样都不需要，12–16 MP 直接全分辨率。
- 只有当用户真的导入 24 MP / 45 MP，才需要 ① 的分带能力。

**结论：① 从"P1 必做"降级为"v1.1 条件触发"** —— 前提是 §14 的问题 3 确认产品不接大 RAW。这能省 **8–12 人日**，也就是把 P1 从 20–30 人日压到 **12–20** 人日。

### 5.2 Vulkan 后端的成熟度：同步提交 + CPU 回读

已在 §3.3 定位。补充要点：

**① 批处理提交（5–8 人日）**
- 当前：`dispatch` 内部 `vkQueueSubmit` + `waitForFences`，每 kernel 一次。
- 目标：`dispatch` 只录制到 `VkCommandBuffer`，`flush` 时一次提交。
- **难点不是提交，是正确性**：`gpu.hpp` 的 `BufferRef` 池依赖"命令缓冲完成后才可复用"。`AGENTS.md` trap 19（"Free is not idle"）记录过这个坑 —— 一次误复用导致 25/27 个渲染用例错误、无崩溃、无报错。所以改造必须同步引入 `VkFence` + 每 buffer 的 `last_used_serial`，并且 `PoolAudit.reclaim_while_encoding` 必须保持 0。
- 收益：30+ 次 synch → 1 次。桌面端估计省 30–80 ms/帧，手机端更多。

**② 零拷贝显示（5–7 人日）**
- 当前：`texture()` 里 `malloc` + `read()` 全量拷回（注释明写 "A headless result is a separate CPU-owned RGBA16 copy"）。
- 目标：渲染结果直接落在 `VkImage`，用 `AHardwareBuffer` 导出，`SurfaceView` 直接合成。Android 侧的链条：
  ```
  VkImage ← VK_EXT_external_memory_android_hardware_buffer
    → AHardwareBuffer → SurfaceTexture/TextureView 或 ANativeWindow → 屏幕
  ```
- 需要扩展 `spk_engine_create(resources_dir, device)` 的 `device` 参数语义：从"`id<MTLDevice>`"泛化成"后端特定的设备描述"，Android 传入 `{VkInstance, VkDevice, ANativeWindow}`。这是 C ABI 的**唯一一处必要变更**，且可以保持向后兼容（传 NULL 走现在的路）。
- 附带收益：预览档（1600 px）本来就不该走全帧回读，这一步能把"拖滑块"的延迟从几十 ms 压到个位数。

**③ 内存池的预算化**
`spk_memory_report` 已经暴露了 `PoolStats`（`live/free/pending/persistent`）。Android 侧要把它接到 `onTrimMemory` / `onLowMemory`：`TRIM_MEMORY_RUNNING_LOW` 时调用一次 trim（`trim_pool` 已存在）。

### 5.3 设备矩阵：驱动是最不可控的变量

Vulkan 在 Android 上是"有，但质量参差"。必须建立实测表，不要靠猜：

| GPU | 代表 SoC | Vulkan | 关注点 |
|---|---|---|---|
| Adreno | 骁龙 8 Gen 1/2/3、7 系 | 1.1 / 1.3 | 通常是最好的一档；`maxStorageBufferRange` 往往大于 128 MiB，**必须实测** |
| Mali | 天玑 9000/9200、Exynos | 1.1 / 1.2 | 历史上 compute 的 `precise` 与 barrier 行为问题较多；TBDR，bandwidth 敏感 |
| Xclipse | Exynos 2200+（RDNA） | 1.3 | 桌面血统，compute 强 |

**必须在 P0 就采到的 7 个数**（一台机器跑一个 probe 就够，`spk_capabilities` + `spk_build_info` 已有部分）：

```
maxStorageBufferRange              ← 决定最大可渲染像素（§5.1）
maxComputeWorkGroupCount[0]        ← 已由 dispatch 折叠处理（65535 自动折 2D）
maxComputeWorkGroupSize[0]         ← 需 ≥ 256
maxComputeWorkGroupInvocations     ← 需 ≥ 256
maxPerStageDescriptorStorageBuffers  ← 需 ≥ 8（见下）
maxDescriptorSetStorageBuffers       ← 需 ≥ 8（见下）
thermals                           ← 连续渲染 3 分钟的降频曲线
```

引擎已经自带启动自检（`vulkan_gpu.cpp:200-207`）：上面四项工作组成员限制**以及两个 descriptor 限制**任一不满足，会直接返回 `"Vulkan device limits are below the current shader layout"` 拒绝启动。这是对的，保留。

**注意 descriptor 这条容易被忽略**：最宽的 kernel 是 `spk_grain_layers` / `spk_grain_layer_one`，各绑 **8 个 storage buffer**（`kKernels[]` 表里可以数出来）。Vulkan 规范只要求 `maxPerStageDescriptorStorageBuffers ≥ 4`，所以理论上存在报 4 就合法的设备 —— 它在启动时会被拒。主流移动 GPU 报 16，但**这一项要在设备矩阵里单独确认**，否则会出现"某台机器打不开"的悬案。

**注意一处必须知道的逻辑**：`probe_cpu_storage_range`（`vulkan_gpu.cpp:321`）会在 **CPU 设备**上把 `maxStorageBufferRange` 抬到 4 GiB —— 那是给 lavapipe 的补丁，只在 `deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU`（且 math probe 显示 fma 未融合）时触发，真实 GPU 保持厂商上报值（代码注释明写 "Any GPU keeps its advertised limit"）。

**这带来一个测试陷阱：Android 模拟器（SwiftShader）把自己报成 CPU 设备，于是它会通过大图渲染，而真机不会。** 所以 §7 的验收里，"模拟器能跑 45 MP" 是**假阳性**，不能作为 R1 已解决的证据。模拟器只能验证功能，不能验证内存上限。

### 5.4 五个 Apple-only 特性：迁移时保持"拒绝"

`engine/PORT_COVERAGE.md` §5 与 `PORT_PLAN.md` 记录：owner 在 2026-10-06 决定，以下五个特性在 Apple 以外**保持拒绝**（`SPK_ERR_USER` + `capabilities.backend.unsupported_features` 明示）：

1. 对比度蒙版（contrast mask，RFC-024）
2. 场景宽容度映射（scene latitude 的"应用"部分，RFC-023）
3. Cineon 数字中间片（DI）
4. 片边 / overscan（RFC-031/032）
5. 日期后背（date back）

**迁移时的建议：v1 保持拒绝，`capabilities` 如实上报。** 但要注意产品含义 —— 对一个"胶片模拟"产品来说，**片边和日期后背是很有辨识度的卖点**。所以：

- `PORT_PLAN.md` 已给出可执行的移植路径与估时（DI 1–1.5 天、latitude 1.5–2 天、contrast mask 2–3 天、overscan 6–10 天、date back 1–2 天，合计 **12–18 人日**）；
- 其中 overscan 的难点是 CoreText/CoreGraphics/vImage → 需换 `stb_truetype` + 自写卷积，且 **Helvetica Neue 不可再分发**，需换 TeX Gyre Heros；
- **建议在 v1.1 补 overscan + date back**（约 8–12 人日），因为这是产品差异化，不是锦上添花。

另外两个已知的"分带不兼容"要在 Android 打开 `striped` 前处理：

```
overscan.cpp:520  "overscan: not supported by the striped executor yet (RFC-032 §27)"
```

即：**片边 + 分带互斥**。这会和 §5.1 的 ① 冲突，必须在做 ① 时一并解决，否则"大图分带渲染"和"带片边"不能同时成立。

### 5.5 与原型的交叉核对：两处必须现在就解决的冲突

`docs/SpektraLab-Android-原型设计说明.html`（2026-10-31 新增）已经把 UI 侧想清楚了，但它与引擎的实际能力有**两处硬冲突**。这两处不解决，UI 会按一个引擎做不到的定义去画。

#### 冲突一：原型是"纯导入"，而产品定位是"直接拍"

原型的三档页面清单（§12.1）里，入口全是「启动 / 打开照片后」：

```
A1 编辑器主屏  ← 启动 / 打开照片后
A2 胶片与印相  ← 底部"胶片"
A3 参数面板    ← 底部"参数"
A4 裁剪模式    ← 工具条 / 片边"取景"
A5 导出页      ← 顶栏 / 底部"导出"
A6 设置        ← 溢出菜单
B1–B5, C1–C3 …
```

**没有任何一处是"打开相机 / 快门 / 拍摄"。** 全文里"拍摄"只出现在两处语义里：胶片模拟的"拍摄 → 显影"流程，以及日期后背要印的"拍摄数据"（EXIF 文字）。`11.6 文件、权限与导出` 讲的也是 Photo Picker / SAF。

而产品定位（本轮已确认）是**"更多的是直接拍"**。所以原型缺一个 P0 入口页：

> **A0 · 拍摄**：取景器（系统预览 ± 近似 LUT）+ 快门 + 镜头/曝光控制 + 拍完进 A1。
> 它需要的新状态：`RAW 能力不可用` / `拍摄中` / `DNG 写入中` / `开发中（进度）` / `拍摄失败（存储满 / 热保护）`。

这一页必须补，否则"直接拍"这个核心场景在原型里根本不存在。

#### 冲突二：原型把「片边 + 日期后背」列为 P0 建议，但 Vulkan 后端**拒绝**这两个特性

原型的 §11.8 首版取舍写：

> **P0 建议有** 片边 + 日期后背 —— 产品差异化最强的一组功能；但规则复杂，需要专门的面板与状态设计

而引擎侧的事实（`engine/PORT_COVERAGE.md` §2、`PORT_PLAN.md`）：

| 特性 | 非 Apple 后端 | 移植代价 |
|---|---|---|
| overscan / 片边（RFC-031/032） | **refused**（`SPK_ERR_USER`，`unsupported_features` 明示） | 6–10 人日，且需 CoreText→stb_truetype、CoreGraphics→自写光栅、vImage→自写卷积，外加换字体 |
| date back / 日期后背 | **refused** | 1–2 人日（依赖片边） |
| Cineon DI | **refused** | 1–1.5 人日 |
| 对比度蒙版 | **refused** | 2–3 人日 |
| 场景宽容度映射（应用） | **refused** | 1.5–2 人日 |

**结论：如果片边+日期后背留在 P0，那 8–12 人日必须从"v1.1"提前到 v1，而且它还与分带互斥（见上）。** 二选一：

- **选项 α**：接受 +8–12 人日，把 overscan/date back 纳入 v1 的关键路径（推荐，因为原型说得对——这是最强的差异化）；
- **选项 β**：把片边+日期后背降到 P1，v1 用"无片边"的胶片外观先上线。

无论选哪个，**原型的 A4「裁剪模式」要覆盖的"片边锁定"状态，和 A2 的"片边开"状态，在引擎尚未移植前必须能表达为"禁用 + 原因"**（原型 §8.3 已经要求"禁用原因必须留出一行字的位置"）。这一点做对了的话，两端可以并行推进。

---

## 6. 工作分解（WBS）

估时按"1 名熟悉 C++/Vulkan 与 Android NDK 的工程师"计。验收标准一律可执行。

### P0 · 可行性冒烟（3–5 人日）—— 先做这个

| # | 任务 | 产出 | 验收 |
|---|---|---|---|
| P0.1 | NDK toolchain + CMake，编译 `engine` 为 `arm64-v8a` | `libspektralab-core.so` | 链接通过，导出 29 个 `spk_*` 符号 |
| P0.2 | glslang 预编译 34 个 `.comp` → `.spv`，随 assets 打包 | `assets/engine/vulkan/*.spv` | 与 Linux 构建的 `.spv` 逐字节一致 |
| P0.3 | 最小 JNI `main` + 一个 1600×1200 的合成输入 | APK | `spk_capabilities` 返回 `render_core: native-vulkan` |
| P0.4 | **真机**跑通 `open → render("preview")` 并落 PNG | 一张图 | 与 Mac 金标 `max |d| ≤ 1` 码值（§7） |
| P0.5 | 采集 §5.3 的 7 个设备指标 | 一张表 | 至少 3 台真机（Adreno / Mali / 各一） |

**P0 的决策价值**：如果 P0.4 在 Adreno 上过了，整个方案的低风险部分就确认了；如果 Mali 上失败，能在 5 天内知道，而不是在 UI 做完之后。

### P1 · 引擎性能与内存（12–20 人日，手机优先口径；接大 RAW 则 20–30）

| # | 任务 | 估时 | 验收 |
|---|---|---|---|
| P1.1 | Vulkan 批处理提交（命令缓冲 + fence + buffer 序列号） | 5–8 | `PoolAudit` 三个必须为 0 的计数仍为 0；30+ 提交 → 1；`parity_metal_vulkan.py` 全绿 |
| P1.2 | 零拷贝显示（`AHardwareBuffer` / `ANativeWindow`） | 5–7 | 预览帧不再全帧回读；拖动滑块到画面更新 < 100 ms（1600 px） |
| P1.3 | **最大可渲染像素策略**：按 `maxStorageBufferRange` 算阈值，超出则降采样（§5.1 ④） | 2–3 | 12 MP 帧在 128 MiB 档设备上能出图；策略有单测 |
| P1.4 | `spk_memory_report` 接 Android 内存回调 | 1 | 低内存下不 OOM，自动 trim |
| P1.5 | （**条件触发**）whole-frame 阶段带化（grain / glare / boost） | 8–12 | `strips == 1` 与 `strips == N` 输出逐字节相同；45 MP 可渲染 |
| P1.6 | （条件触发）`choose_strip_height` 预算策略 | 1–2 | 给定 `strip_budget_bytes`，峰值显存 ≤ 预算 |
| P1.7 | （可选）定点 fp16 存储 | 5–10 | 逐 kernel 过 1 码值门槛 |

> P1.5/P1.6 只在"必须支持 >11 MP 全分辨率"或"必须支持大 RAW"时才做。见 §5.1 ④。

### P2 · 平台宿主（24–33 人日）

| # | 任务 | 估时 | 说明 |
|---|---|---|---|
| P2.1 | LibRaw 0.22.2 交叉编译（`LIBRAW_NOTHREADS` 已在 CMake 里，需确认 Android 的 pthread 路径） | 3–4 | `raw_decoder.cpp` 本身**无 Win32 依赖**，改动很小 |
| P2.2 | 资源解压 + 版本戳 | 2 | §4.3 方案 A |
| P2.3 | 编辑持久化（sidecar） | 3–4 | macOS 用 `~/Library/Application Support/.../Sidecars/`；Android → `filesDir/sidecars/`，JSON 结构可沿用 |
| P2.4 | 导出 | 4–6 | PNG16 / JPEG / TIFF16 + ICC；落 `MediaStore`（DCIM）或 SAF。**复用 `host/output.cpp` 的可移植写出器，不要用 `io/image_writer.cpp` 的 Win32 事务版** |
| P2.5 | JNI 门面 + 线程/取消/进度/生命周期 | 4–6 | §4.2；`spk_cancel` / `spk_progress` 已有 |
| P2.6 | 色彩管理 | 3–5 | 见 §6.1 |
| P2.7 | 相册/文件选择（Photo Picker + SAF） | 2 | 无需存储权限（Android 13+ `PickVisualMedia`） |
| **P2.8** | **普通图导入：把 `image_io.cpp` 接到 host**（JPEG/PNG/TIFF，含 ICC、16 bit、方向） | **2–3** | §4.4.1 的"没接线"那条；解码器已存在且有测试 |
| **P2.9** | **HEIC/HEIF/AVIF 导入：系统 `ImageDecoder` → `ColorSpace` → 线性 ProPhoto** | **4–6** | §4.4.2；唯一新增的颜色代码 |
| **P2.10** | **应用内拍摄 RAW：Camera2 + `DngCreator` → DNG → LibRaw** | **5–8** | §4.4.4；含能力检测与降级、权限、EXIF 回写 |

#### 6.1 色彩管理是 Android 上被低估的一块

macOS 侧走的是：ProPhoto RGB 内部 → 显示 P3 → EDR。Android 侧对应：

| 关注点 | Android 手段 |
|---|---|
| 广色域显示 | `SurfaceView` + `AHardwareBuffer`（`AHARDWAREBUFFER_FORMAT_RGBA_FP16`）+ `ColorSpace.Named.DISPLAY_P3` |
| HDR / EDR | Android 13+ `Display.HdrCapabilities`；但**手机 HDR 面板的峰值亮度与 EDR 语义都弱于 Pro Display XDR**，建议 v1 只做 SDR + P3 |
| 导出打 ICC | `android.graphics.ColorSpace` 只能打小集合；自定义 ICC 需把 `.icc` 字节直接写进 PNG iCCP / TIFF 标签（`host/output.cpp` 已有先例） |
| 8 bit vs 16 bit 链路 | 预览可 8 bit（Windows 侧就是 "the GUI shows an 8-bit SDR texture"），导出必须 16 bit |

引擎侧已有 `spk_output_transform` 负责输出变换（`PORT_COVERAGE.md` 记录 P3 导出经 lcms 转换后与 sRGB 差 < 0.6 % MAE），所以**不需要在新平台重做色彩科学**，只需要正确地把变换后的像素送进 Android 的显示/编码通路。

### P3 · UI（25–40 人日，取决于原型）

见 §8。这一块的估时**不该现在定死**，因为原型未完成。

### P4 · 特性补齐（可选，12–18 人日）

见 §5.4。建议 v1.1 只补 overscan + date back（8–12 人日）。

### P5 · 打磨与发布（10–15 人日）

真机矩阵回归、热降频策略、崩溃上报、包体裁剪、上架合规。

### 合计（按 2026-10-31 的确认重算）

**前提变了两次**：① 手机优先、不接大相机 RAW；② `android/main` 已经做到 S6（探针 + JNI 引擎 + Compose 编辑器 + 导出/撤销）。所以下表是**剩余工作**，不是从零。

| 项 | 状态 | 剩余人日 |
|---|---|---:|
| S1 设备探针 / 引擎交叉编译 / SPIR-V | ✅ 已有 | 0 |
| S2 Vulkan 批处理提交 | ✅ 已有（`engine/vulkan-batching`） | 0 |
| S3/S4 JNI 门面 + 资源解压 + 内存预算 | ✅ 已有 | 0 |
| S5 Compose 编辑器（主屏/胶片/参数/裁剪/导出/关于） | ✅ 已有 | 0 |
| S6 导出、编辑持久化、撤销、后台导出 | ✅ 已有 | 0 |
| 降采样/最大像素策略（§5.1 ④） | ⬜ 待做 | 2–3 |
| **普通图导入接线**（JPEG/PNG/TIFF，复用 `image_io.cpp`） | ⬜ 待做 | 2–3 |
| **HEIC/HEIF 导入**（系统 `ImageDecoder` → 线性 ProPhoto） | ⬜ 待做 | 4–6 |
| **拍摄 RAW**（C3，Camera2 + DngCreator，含多摄与降级） | ⬜ 待做 | 5–8 |
| **HEIC 导出**（libheif + 10-bit + ICC + 增益图，§4.7） | ⬜ 待做 | 6–9 |
| **取景器 LUT 烘焙 + 近似预览**（§4.6.1） | ⬜ 待做 | 4–6 |
| 三处「禁用 + 原因」态（片边/日期/场景置位，§4.5.1） | ⬜ 待做 | 1–2 |
| 原型缺口状态（无 RAW / 无权限 / 开发中 / 热保护，§4.5.3） | ⬜ 待做 | 2–3 |
| 胶卷（Roll）数据模型 + 与拍摄打通（§4.5.2） | ⬜ 待做 | 3–4 |
| 真机矩阵回归 + 热降频 + 包体 + 上架 | ⬜ 待做 | 8–12 |
| **v1 合计** | | **37–56** |
| v1.1 片边 + 日期后背移植（§4.5.1） | 推后 | 8–12 |
| v1.1 分带渲染（接大图时才做，§5.1 ④） | 条件触发 | 8–12 |

**关键判断：从 ~85 人日降到 ~45 人日**，因为 `android/main` 里已经有一个能跑的编辑器。真正的增量集中在三块：**拍摄（含取景器 LUT）≈ 9–14 人日、HEIC 编解码 ≈ 10–15 人日、胶卷模型 ≈ 3–4 人日**。

> 这个数字的前提是"`android/main` 的代码质量与你 README 描述一致"。**我没有逐行审过它的 22 个 Kotlin 文件和 33 KB 的 `engine_jni.cpp`** —— 按你的要求"不要全信"，建议在动手前先做一次半天到一天的代码走查，重点看三处：`engine_jni.cpp` 的线程/生命周期、`EngineClient.kt` 的取消与进度、`ExportWorker.kt` 的前台服务声明。走查后再校准上表。

**与第一版方案的差异**（由"手机优先、不接大 RAW"这一约束带来）：

- P1 从 20–30 降到 12–20：whole-frame 带化（8–12 人日）从"必做"变成"条件触发"（§5.1 ④）
- P2 从 18–25 升到 24–33：新增了拍摄 RAW（5–8）、HEIC 导入（4–6）、普通图接线（2–3）
- 合计几乎持平（76–115 → 74–113），但**风险性质变了**：从"要改造渲染器才能显示一张普通照片"变成"要把三条输入通道接对"

单人体力约 4.5–6 个月；两人并行（一人引擎、一人 UI）约 2.5–3.5 个月。

---

## 7. 验收策略：不要重新发明，直接复用

这是这个仓库最值钱、也最容易被忽略的部分。

### 7.1 现成的金标准工具

`engine/tests/parity_metal_vulkan.py`（470 行，在 `Windows_Linux` 分支上）已经在做"Metal 引擎 vs Vulkan 引擎，同一输入，逐像素比对"。它的验收门槛是：

```
确定性用例：max |d| ≤ 1 个码值（8 bit），mean |d| ≤ 0.01 码值
           CIEDE2000 p99.9 ≤ 0.1，max ≤ 1.0
```

而且这个门槛**足够紧**：文档记录"0.02 EV 的曝光改动（mean 0.058 码值）会 FAIL"。测试输入是合成的：色谱、曝光楔、三层色相扫描、暗部高饱和红（GitHub issue #12）、硬边、点光源。

**Android 用法**：

```
1. 在 Mac 上跑 Metal 引擎，落 build/parity/metal/*.npy     ← 金标
2. Android 上跑 Vulkan 引擎，落 .npy，adb pull 回来
3. 在 Mac 上 compare                                        ← 一次判定
```

这一步把"手机上的渲染对不对"变成**一个可自动化的二值判断**，而不是靠眼睛。

### 7.2 三层验收

| 层 | 工具 | 频率 |
|---|---|---|
| 单元/kernel | `ctest` 在 **lavapipe**（Mesa CPU Vulkan）上 —— `PORT_COVERAGE.md` 记录 14/14，`windows_UI/README.md` 记录 16/16 | 每次提交 |
| 整图 | `parity_metal_vulkan.py`（Mac 金标 vs Android） | 每个 PR |
| 真机 | 设备矩阵 × 一组真实 RAW（12 MP / 24 MP / 45 MP） | 每个里程碑 |

**注意 lavapipe 的价值**：它是 CPU 设备，能在 GitHub Actions 上跑，不需要 GPU。这正是 §5.3 里"CPU 设备探针"存在的原因。但它也意味着 **CI 绿 ≠ 手机绿** —— `PORT_COVERAGE.md` 明确写了："Nothing here was run on a real GPU"。

### 7.3 测试输入要先设计

macOS 侧的测试夹具（`tests/Test_image/`）在 `.gitignore` 里，且体积巨大。Android 需要自己的：

- 合成帧（`parity_metal_vulkan.py` 自带的那套，可复用）
- 3 张真实 RAW：小（12 MP）、中（24 MP）、大（45 MP）—— 注意 `AGENTS.md` trap 26：**"跳过的测试和跑完的测试都报 0 failures"，要看耗时而不是失败数**
- 一张超宽 / 超长（验证 `maxComputeWorkGroupCount` 折叠）

---

## 8. UI 选型

**前提：前端原型未完成。** 所以这里给的是"约束 + 对比"，不是最终决定。

### 8.1 一个不该变的结构性决定

无论选哪个 UI 框架，**"引擎 + host 层"都用同一份 C++**（`engine/src/{core,pipeline,io,gpu,desktop}`）。这一点已经成立 —— `preview_host.cpp` 无 Qt、无 Win32，就是为此准备的。UI 框架只影响它上面那一层。

```cpp
// 已存在的、可复用的核心层（engine/src/desktop/preview_host.hpp）
class PreviewHost {
    FramePtr open_raw(path, DecodeMode, RenderSettings);
    FramePtr render(RenderSettings);          // 只发变更项；打印侧改动走 spk_reprint
    void     export_tiff(Frame&, path) const;
};
```

Android 要做的就是给它套一层 JNI + 一个 Kotlin 的 `Flow`/`StateFlow` 包装。

### 8.2 三个方案

| | A. Qt 6 Quick | B. Kotlin + Compose + JNI | C. Flutter + FFI |
|---|---|---|---|
| **复用度** | 高：`windows_UI/Main.qml` 550 行 + 6 个 C++ 文件可直接搬 | 中：复用 C++ 核心，**重写全部 UI** | 低：复用 C++ 核心，重写全部 UI，还多一层 Dart |
| **原生体验** | 差（Qt 控件不像 Android） | **最好**（Material 3、预测式返回、分享面板、系统字体） | 中 |
| **图像/手势性能** | 尚可（QSG 纹理节点） | 好（`Compose Canvas` + `graphicsLayer`；重度缩放建议 `SurfaceView` 叠 Canvas） | 尚可 |
| **广色域/HDR** | 需自己过 `ANativeWindow` | **最直接**（`HardwareBuffer` + `ColorSpace` 是平台 API） | 需插件 |
| **包体** | +30–45 MB（Qt 运行时） | +2–5 MB | +8–12 MB |
| **许可证** | LGPL-3（动态链接可接受，但义务繁琐） | Apache-2（无义务） | BSD-3（无义务） |
| **工具链风险** | Qt for Android 6.11 可用但生态小 | **最主流** | Firestore/插件生态好 |
| **适合场景** | 想**最快看到能跑的编辑器**（2 周出一个 Windows 风格的可运行版） | 要做**长期的原生产品** | 团队是 Flutter 栈 |

### 8.3 建议

**选 B（Kotlin + Compose + JNI）**，理由按权重排序：

1. 这是一个**图像编辑器**，不是表单应用。它需要：精确的 1:1 像素级缩放（`AGENTS.md` trap 27/33 讲的"每个显示帧的表面必须显示 canvas 那一帧"在 Android 上是同一类问题）、广色域显示、捏合手势、长按对比。这些在 Compose/原生 API 上都是第一等公民。
2. 原型还没定 → 现在**没有沉没成本可保**，反而应该趁重设计选一个不锁死长期成本的栈。Qt 会把 UI 层锁在 Qt 生态里十年。
3. 包体：一个胶片模拟 App，用户对 45 MB 的 Qt 运行时是敏感的。
4. GPL-3.0 的代码 + LGPL 的 Qt + Play Store 上架，是可行的，但合规面更宽；Compose 没有这个问题。

**但如果目标是"两周内让老板看到手机上能跑"**，A 是合理的**技术验证分支**：把 `windows_UI` 原样编到 Android，不做任何美化。它能在 P1 之前就证明"引擎在手机上能出图"，而且成本只有 3–5 天。**这与 P0 冒烟目标重合，可以作为 P0 的可视化载体。**

> **与原型说明书的核对（2026-10-31）**：`docs/SpektraLab-Android-原型设计说明.html` §11.1 独立列出了同样的三条路线（A · Tauri 2 + React / B · 原生 Compose + JNI / C · Qt Quick），并且对本方案同为 A 的那条给出了一致的判断 —— *"最快出可运行原型，但功能会明显缩水，不适合作为完整产品"*（它指的是 Qt 那条）。**两份文档没有分歧**，所以 §8.3 的结论可以直接采用；`desktop/` 的 Tauri 路线则建议只用于技术验证，不进产品关键路径（WebView 画布 + 广色域是已知弱点）。

### 8.4 给原型设计的输入

原型说明书已经覆盖了绝大部分（断点、手势映射、信息密度、禁用原因表达），本表只列**引擎侧强加的、设计必须知道的**约束：

| macOS 的做法 | Android 必须改成 | 依据 |
|---|---|---|
| 左轨 + 右轨 + 画布 + 胶片条（四区） | 手机竖屏放不下。原型 §11.2 的断点方案（Compact / Medium / Expanded）已经解决了这一条 | 原型 §11.2 |
| 8 个快捷键（←/→ 切帧等） | 触屏无键盘。原型 §11.3 的手势映射表已经覆盖 | 原型 §11.3 |
| `NSTextField` 重命名（trap 32 的单键快捷键冲突） | 输入法 `ImeAction`；注意 `AGENTS.md` trap 32 的教训在触屏上以"输入法弹出时误触画布"的形式重现 | `AGENTS.md` trap 32 |
| `Exporter.swift` 写 TIFF/.cube | `MediaStore` 落 DCIM + 分享 Intent；`.cube` 走 SAF | — |
| **（原型缺）拍摄入口** | 需新增 **A0 拍摄页**：取景器 + 快门 + 镜头/曝光；状态含「RAW 不可用 / 拍摄中 / DNG 写入 / 开发中 / 失败」 | §4.4.4、§5.5 冲突一 |
| **（原型列为 P0）片边 + 日期后背** | 引擎当前 **refused**；要么 +8–12 人日提前移植，要么降 P1 并在 UI 上表达为"禁用 + 原因" | §5.4、§5.5 冲突二 |

---

## 9. 构建与工具链

### 9.1 目标配置

| 项 | 取值 | 理由 |
|---|---|---|
| ABI | `arm64-v8a`（必需）、`x86_64`（模拟器，可选）、`armeabi-v7a`（放弃） | 32 位设备的可用内存根本跑不了这个引擎 |
| `minSdk` | **29**（Android 10） | Vulkan 1.1 的可选性、`AHardwareBuffer`、Scoped Storage、`ColorSpace`；24 虽然能跑 Vulkan 1.0，但设备质量太差 |
| `targetSdk` | 36 | 跟当前 SDK（本机已装 `android-36`） |
| NDK | r27+ | 与 Vulkan-Headers 1.4.341 兼容 |
| C++ | C++20（引擎已要求） | `cxx_std_20` |
| 构建 | `externalNativeBuild` + CMake 3.22+ | 引擎的 `CMakeLists.txt` 已经是 3.22 |

### 9.2 SPIR-V：不要在生产构建里跑 glslang

`glslangValidator` 是**主机**工具。Android 构建的做法：

```
CI / 开发机：glslangValidator -V --target-env vulkan1.0 → 34 个 .spv
            → 作为 assets 打进 APK（或作为 C++ 数组内联）
```

**理由**：`engine/resources/` 本身就是"tracked 的烘焙产物"（AGENTS.md 明确写了 15 MB 烘焙数据被跟踪，正是为了让全新 clone 不需要重新烘焙）。SPIR-V 应当照这个先例处理 —— **编译产物入库**，构建只做拷贝。这也顺便保证了 Android 与 Linux/Windows 用的是**同一份 SPIR-V**（可验证 sha256）。

### 9.3 这台机器的现状（供参考）

| 组件 | 状态 |
|---|---|
| Android SDK | ✅ `%LOCALAPPDATA%\Android\Sdk`，platforms 34/35/36/36.1/37.0，build-tools 34–36.1 |
| 系统镜像 | ✅ `android-36 google_apis_playstore x86_64`（可跑模拟器；SwiftShader 支持 Vulkan 1.1，**功能验证可以，性能验证不行**） |
| **NDK** | ❌ 未安装 |
| **SDK CMake** | ❌ 未安装 |
| WSL | ✅ Ubuntu（Running）—— 可作为 Linux 构建/CI 复现环境 |
| JDK | ✅ Temurin LTS |
| Vulkan SDK / glslang | ❌ 未安装 |

**P0 前需要补**：`sdkmanager "ndk;27.2.12479018" "cmake;3.22.1"`，以及 Vulkan SDK（只要 glslang 与 headers）。

### 9.4 Git：fork + 上游同步（"以后合并不痛苦"的具体做法）

**已在本机执行**（2026-10-31）：

```
origin    → https://github.com/lyizhuo/SpektraLab.git          （你的 fork）
upstream  → https://github.com/JamesQiu2005/SpektraLab.git     （原作者）
```

分支实际布局（核实过）：

| 分支 | 归属 | 作用 |
|---|---|---|
| `origin/main` | fork | 上游 main 的镜像 |
| `origin/Windows_Linux` | fork | 上游跨平台线（Vulkan/LibRaw/Qt） |
| **`origin/android/main`** | **你的** | **Android 工程主体**（S1–S7，`android/` 目录） |
| `origin/engine/vulkan-batching` | 你的 | **需要改上游引擎时的载体** |
| `origin/engine/glslc-support` | 你的 | 同上 |

#### 让上游合并不痛的三条规则

**规则 1：上游代码永远不直接改。**
`android/` 已经做对了 —— `add_subdirectory(<repo>/engine)`，`engine/**` 保持 upstream 原样。这样 `git merge upstream/Windows_Linux` 时 `engine/` 永远是"上游怎么说就怎么样"，不会产生冲突。

**规则 2：确实要改上游时，走 `engine/<slug>` 分支，并让 `android/main` 只做 merge，不做本地改写。**

```bash
# 需要给引擎加一个能力（例如 HEIC 导出出口）：
git switch -c engine/heic-export upstream/Windows_Linux
# ... 只改 engine/**，按上游的规范（AGENTS.md、parity 门槛、PORT_COVERAGE.md）
git commit -m "..."

git switch android/main
git merge --no-ff engine/heic-export        # 只在这一个提交里接触 engine/
```

好处：**冲突只可能出现在 merge 那一刻，而且只出现在那一个 engine 分支上**；`android/main` 本身对 `engine/**` 零本地修改。

**规则 3：定期把上游基线合并进 `engine/*` 分支，而不是合并进 `android/main`。**

```bash
# 每月一次：
git fetch upstream Windows_Linux
git switch engine/heic-export && git merge upstream/Windows_Linux
git switch android/main && git merge --no-ff engine/heic-export
```

如果暂时没有 `engine/*` 分支，这一步就是一次干净的 `git merge upstream/Windows_Linux`（因为规则 1，`engine/` 无本地改动）。

#### 日常命令

```bash
git fetch upstream Window_Linux android/main   # 上游有更新时
git switch android/main && git rebase origin/android/main
git push origin android/main
```

> **一个必须知道的坑**：这个仓库 **614 MB**，全量 `git fetch` 很慢（本次一次 fetch 跑了好几分钟并留下 `shallow.lock`）。日常用 `--depth 1` 单独取分支：
> ```bash
> git fetch --depth 1 origin android/main
> ```
> 浅仓库里偶尔残留 `.git/shallow.lock`，删掉即可（本次已遇到）。

### 9.5 CI

- CI：新增一个 `android` job —— 在 Ubuntu runner 上用 NDK 交叉编译 + lavapipe 跑 `ctest`（**不需要 GPU**）。与现有的 Windows/Linux job 同构。
- 真机测试：不能进 CI（没有农场），改为**每里程碑人工跑 `scripts/android-probe.sh all`**（`android/` 已经写好了这个脚本！），并把结果记进 `android/DEVICE-BASELINE.md`。
- 跨平台像素比对：`scripts/parity-check.sh`（S7）已经存在，对应 §7 的金标准策略。

---

## 10. 许可证与合规（不能事后补）

| 组件 | 许可证 | Android 分发义务 |
|---|---|---|
| SpektraLab 本体 | **GPL-3.0-or-later** | APK 分发即触发：必须提供完整对应源码（含你的修改），并保留版权声明。**Google Play 允许 GPL 应用**，但要在应用内/商店页给出源码获取方式 |
| `engine/resources/profiles/` | **CC BY-SA 4.0** | 必须署名（attribution）+ 相同方式共享。`Tools/bundle-licenses.sh` 已把文本打进 bundle，Android 要做同样的事（`assets/licenses/`，About 页可读） |
| `resources_product/`（黑白胶片） | 见其 `NOTICE.md` | 同上，逐条核对 |
| LibRaw 0.22.2 | **LGPL-2.1 或 CDDL-1.0（双许可）** | 当前 CMake 选的是 LGPL。Android 静态链接 LGPL 需要提供可重链接的目标文件；**更省事的做法是用 CDDL-1.0 选项**（文件级 copyleft，静态链接无义务）。建议在 Android 构建里显式选 CDDL 并在 `licenses/` 里放 CDDL 全文 |
| stb | Public Domain / MIT | 保留声明 |
| volk | MIT / Apache-2.0 | 保留声明 |
| Vulkan-Headers | Apache-2.0 / MIT | 仅编译期，不随包 |
| Qt（若选 A） | LGPL-3 | 动态链接 + 允许用户替换 Qt 库；需在 About 提供 Qt 源码链接 |

**一条硬约束（`AGENTS.md` "Do not"）**：不得加入 GPL 不兼容的依赖。Android 侧尤其要小心闭源 SDK（某些崩溃上报/AI 库）。

---

## 11. 风险登记册

| # | 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|---|
| R1 | 目标设备 `maxStorageBufferRange` = 128 MiB，12 MP 帧放不下 | **高** | 中（手机优先口径） | 阈值是 11.18 MP，差 7% → **降采样策略（§5.1 ④，2–3 人日）**即可覆盖；只有接大 RAW 才需要分带（8–12 人日） |
| R2 | Mali 驱动在 compute + 多 SSBO 上有 bug | 中 | 高 | 设备矩阵前置（P0.5）；必要时按 GPU 家族开关特性；`precise` 已是正确用法 |
| R2b | 某设备的 `maxPerStageDescriptorStorageBuffers < 8`（`spk_grain_layers` 绑 8 个），启动即被拒 | 低 | 中 | P0 的设备探测必须包含这两项；若命中，需把 grain 的两个 kernel 拆成两次 dispatch（各 ≤6 个 binding） |
| R3 | Vulkan 同步提交未改造，手机端每帧 30+ 次 submit，交互卡顿 | **高（确定发生）** | 高 | P1.1（5–8 人日），**必须做，不是可选** |
| R4 | 全帧回读导致预览迟滞 | **高（确定发生）** | 中 | P1.2（5–7 人日） |
| R5 | 45 MP / 100 MP RAW 解码即 OOM | 高 | 中 | 输入尺寸上限 + 解码前读元数据拒绝；导出时才全分辨率 |
| R6 | 热降频导致"渲染很慢"的用户投诉 | 高 | 中 | 热状态监听 + 自动降档 + 通知进度；`ARCHITECTURE.md` trap 17 的教训（"这台机器不是基准"）在手机上更极端 |
| R7 | 片边（overscan）与分带互斥（`overscan.cpp:520`） | 中 | 中 | 做 P1.3 时一并解决，或在文档里明确"带片边时不分带" |
| R8 | 五个 Apple-only 特性缺失导致产品力不足 | 中 | 中 | v1 如实上报 `unsupported_features`；v1.1 补 overscan + date back |
| R9 | `spk_engine_create` 的 `device` 参数语义变更破坏 ABI | 低 | 中 | 保持 NULL 兼容；新参数走独立的 `spk_engine_create_with_surface` |
| R10 | 端到端色彩在广色域屏上偏差（trap 27 的同类问题） | 中 | 中 | 沿用 trap 27 的做法：**用一条不需要"before"图的检查**——窗口背景色与画布 letterbox 必须落在同一像素值 |
| R11 | 团队不熟悉 Vulkan/NDK | 中 | 高 | P0 冒烟本身就是学习曲线；`engine/LINUX_WINDOWS.md`、`PORT_COVERAGE.md` 已是很好的交接文档 |
| R12 | **模拟器假阳性**：SwiftShader 报 CPU 设备 → 抬升 SSBO 上限 → 大图在模拟器通过、真机失败（§5.3） | **高** | 中 | 内存类验收**只认真机**；CI 上跑 lavapipe 只作功能回归 |
| R13 | Android 用 LibRaw 的 `dcraw` 通用矩阵，macOS 用 Apple RAW 引擎 → **同一张 DNG 两端颜色不同** | **高（确定发生）** | 中 | 认定为既有分歧并在 UI/文档说明；若要一致，按机型做矩阵标定（§4.4.4） |
| R14 | 目标机型没有 `RAW` 能力，或 `RAW_SENSOR` 质量差（无镜头阴影校正、偏色） | 中 | 中 | 能力检测 + 降级到系统 JPEG/HEIC；建立机型白名单（§4.4.4） |
| R15 | HEIC 10 bit HDR 被退成 8 bit sRGB，信息不可逆丢失（`AGENTS.md` trap 11 的同类） | 中 | 中 | 解码必须走 `ColorSpace` → 线性 ProPhoto，禁止 `copy(ARGB_8888)`；加一条 16 bit 输入的回归用例 |
| R16 | **产品侧对"厂商计算摄影"的期望落空**：有人会问"为什么不能像原厂相机那样出 50 MP Ultra RAW" | **高（几乎必然发生）** | 中 | 提前对齐口径：第三方只能拿 binned 单帧 RAW（§4.4.6）；要接近原厂效果只能自己做多帧合成，或让用户从系统相机拍完再导入 |
| R17 | 为挽回厂商效果而把厂商 DNG 直接喂给胶片模型，导致**物理叙事崩塌 + 颜色不可复现** | 中 | 高 | 明确"便捷模式（导入）"与"RAW 模式（自拍）"两条产品线；便捷模式输出标记为近似（同 trap 12） |

---

## 12. 里程碑

| 里程碑 | 内容 | 完成标志 |
|---|---|---|
| **M0**（第 1–2 周） | P0 冒烟 + 设备矩阵首测 | 真机出图且过 1 码值门槛；3 台机的 6 个指标入库 |
| **M1**（第 3–7 周） | P1 引擎改造 | 45 MP 可分带渲染；提交次数 30+ → 1；预览无全帧回读 |
| **M2**（第 5–10 周，与 M1 并行） | P2 平台宿主 | 能打开真实 RAW、能导出 PNG16/JPEG、编辑能持久化 |
| **M3**（第 8–14 周） | P3 UI（依赖原型） | 完整编辑闭环 |
| **M4**（第 15–17 周） | P5 打磨 + 内测 | 设备矩阵全绿；包体/崩溃/热达标 |
| **v1.1** | P4 特性补齐 | overscan + date back |

---

## 13. 建议的下一步（可以今天就开始的四件事）

1. **切分支并补工具链**（半天）
   ```bash
   git fetch origin Windows_Linux
   git switch -c android origin/Windows_Linux
   # 然后装 NDK + SDK CMake + Vulkan SDK（glslang）
   ```

2. **做 P0.1 + P0.2**（2 天）：NDK 交叉编译 + SPIR-V 预编译，先不要 UI —— 只验证 `.so` 能链出 29 个符号、34 个 `.spv` 与 Linux 产物逐字节相同。

3. **做 P0.3 + P0.4**（2 天）：一个小 `Activity` + 合成输入 + 落 PNG，与 Mac 金标比对。**这一步的结果决定 R1/R3 的严重程度，也决定后面 3 个月的排期。**

同时可以先并行做的三件"零风险"的事：
- 用 `windows_UI`（Qt）编一个 Android 版做**技术演示**（3–5 天），它的价值和 P0 重合；
- 把 `engine/PORT_COVERAGE.md` 复制成 `engine/ANDROID_COVERAGE.md` 的空表，先列好要填的行；
- **做完 §4.4 的输入层冒烟**（1–2 天，与 P0 并行）：不碰引擎，只验证三件事——(a) 系统 `ImageDecoder` 解一张 HEIC 能拿到 `ColorSpace` 且不是 8 bit sRGB；(b) 复用 `image_io.cpp` 解一张带 ICC 的 JPEG 得到线性 ProPhoto；(c) 目标机上 `RAW_SENSOR` + `DngCreator` 出的 DNG 能被 LibRaw 读开。**这三条中任一条不通，都会在 P2 才暴露，而那时 UI 已经写完了。**

---

## 14. 需要你确认的问题

**已定的两个（2026-10-31 确认）**：

- ✅ **拍摄入口进 v1。** Figma 已有 C3 拍摄屏（§4.5）。工程 +5–8 人日（P2.10），另加取景器 LUT 烘焙 4–6 人日（§4.6.1）。
- ✅ **片边 + 日期后背不进 P0。** 引擎在 Vulkan 后端 refused（§4.5.1），v1 只做"禁用 + 原因"，移植（8–12 人日）推到 v1.1。
- ✅ **导出要支持 HEIC，且色彩必须守住**（§4.7）。
- ✅ **目标约 12 MP，接受 binned RAW**（§4.6.4）。

其余会实质影响排期的（建议在原型评审前定掉）：

1. **UI 技术栈**：Compose（推荐）还是 Qt Quick？原型 §11.1 与本文 §8.3 结论一致，倾向 Compose。
2. **目标设备档位**：只做旗舰（骁龙 8 系 / 天玑 9 系），还是也要覆盖中端？决定 §5.1 ④ 的降采样策略要覆盖到哪一档。
3. ~~最大输入尺寸~~ —— **已确认：手机优先，不接大相机 RAW。** 因此 §5.1 ① 的分带改造降为"条件触发"。**但仍需确认一条边界**：相册导入如果收到一张 24/45 MP 的 DNG/JPEG，是 (a) 降采样到设备上限后出图，还是 (b) 直接拒绝并提示？(a) 体验好，(b) 诚实。
5. **是否要与已存在的移动端仓库对齐**？`PRD-v1.4.0.md` 提到 "the mobile repo's `server/logs/`" 与 "The mobile `API-SPEC.md`"，说明已有一个手机端工程（TestFlight 发布）。Android 是并入它，还是独立建？日志上报、账号、更新检查这些是否复用。

本次调研新增的三个问题：

6. **拍摄 RAW 是 v1 必需，还是 v1.1？** 它值 5–8 人日，但它是"胶片模拟相机"这个定位的**唯一忠实路径** —— 相册导入的 HEIC/JPEG 已经把厂商色调曲线烘焙死了。如果不做，产品本质上是一个"给现成照片套滤镜"的工具。
7. **LibRaw 的 `dcraw` 通用矩阵可以接受吗？** 这决定 Android 版与 macOS 版对同一张 DNG 会不会出不同颜色。若要一致，需给 Top-N 机型做相机矩阵标定（每机型约 1–2 人日 + 一张色卡实拍）。
8. **取景器要不要实时胶片预览？** v1 建议先用"近似 LUT"（§4.4.5）。若要做"对 RAW 跑的简化管线"，参考 MotionCam 的做法，另计 8–15 人日。
9. **要不要自己做多帧合成（计算摄影）？** 这是"手机 RAW 画质能不能打"的关键。MotionCam 用光学流 + 高斯金字塔，Silverpoint 用 6 帧对齐平均。**估 10–20 人日**，且需要真机调。不做的话，暗光画质会明显弱于原厂相机 —— 但这是"物理胶片"叙事之外的加分项，不是必需项。

---

## 附录 A：本方案引用的关键文件

| 用途 | 路径 |
|---|---|
| GPU 抽象（5 个动词） | `engine/src/gpu/gpu.hpp` |
| Vulkan 后端 | `engine/src/gpu/vulkan_gpu.cpp`（同步提交见 `flush()`；回读见 `texture()`） |
| 34 个 compute kernel | `engine/src/shaders/vulkan/*.comp` |
| 渲染节点与阶段表 | `engine/src/pipeline/pipeline.cpp`（`kFilmStages`/`kPrintStages` 在 2071–2087；`choose_strip_height` 在 2119） |
| 分带执行器 | `engine/src/pipeline/pipeline.cpp:2157` `run_stages_striped`；`engine/src/pipeline/strip.hpp` |
| 中间帧格式（12 B/px） | `engine/src/pipeline/image.hpp` |
| C ABI | `engine/include/spektrafilm/spk_engine.h` |
| 可复用的宿主层 | `engine/src/desktop/preview_host.hpp/.cpp` |
| RAW 解码（可移植） | `engine/src/io/raw_decoder.cpp` + `engine/cmake/LibRaw.cmake` |
| **非 RAW 解码器（可移植，含 ICC）** | `engine/host/image_io.hpp`（契约，四种无 profile 的约定）/ `image_io.cpp`（`parse_icc`、`Trc`、`apply_orientation`、16 bit PNG） |
| **macOS 的输入格式与 flat 解码** | `modern_UI/Spektrafilm/Spektrafilm/Import/ImageDecoder.swift`（`:110-113` 扩展名；`:266` `decodeFlat`） |
| 输入颜色约定 | `engine/src/core/params.hpp:408-409`（`input_color_space="ProPhoto RGB"`、`input_cctf_decoding=false`） |
| 分带/特性开关（已在 API 上） | `engine/src/core/params.cpp:98-100` |
| 未移植特性的桩 | `engine/src/pipeline/windows_unported.cpp` |
| 移植路径与估时 | `engine/PORT_PLAN.md` |
| 覆盖率表（建 Android 同款的模板） | `engine/PORT_COVERAGE.md` |
| 整图验收工具 | `engine/tests/parity_metal_vulkan.py`（`Windows_Linux` 分支） |
| 内存模型与实测数字 | `ARCHITECTURE.md` §4、§8.7 |
| 陷阱清单（务必通读） | `AGENTS.md` traps 5/6/9/11/18/19/21/23/27/33 |

---

## 附录 B：外部调研来源（2026-10-31 核实）

Android 官方文档：

| 主题 | 链接 |
|---|---|
| `CameraCharacteristics`（`getAvailableCaptureRequestKeys` / `getAvailableSessionKeys`，API 28） | https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics |
| `CameraExtensionCharacteristics`（`EXTENSION_HDR`=3、`EXTENSION_NIGHT`、`EXTENSION_BOKEH`、`EXTENSION_FACE_RETOUCH`，API 31/33） | https://developer.android.com/reference/android/hardware/camera2/CameraExtensionCharacteristics |
| `DngCreator`（`writeImage(OutputStream, Image)`、`writeByteBuffer(...)`、`writeInputStream(...)`，API 21） | https://developer.android.com/reference/android/hardware/camera2/DngCreator |
| `ImageCapture`（`OUTPUT_FORMAT_RAW=2`、`OUTPUT_FORMAT_RAW_JPEG=3`） | https://developer.android.com/reference/androidx/camera/core/ImageCapture |
| `PowerManager`（`getThermalHeadroom(int)`、`addThermalStatusChangedListener`） | https://developer.android.com/reference/android/os/PowerManager |

社区项目（用于判断可行性，**不是依赖**）：

| 项目 | 星数 | 与本方案的关系 |
|---|---:|---|
| `f0enix/motioncam` | 85 | Camera2 RAW 多帧对齐合成；GPU 简化实时预览。**多帧合成的参考实现** |
| `reki-reki/Silverpoint` | 1 | 与本项目形态最接近：Camera2 RAW → 自研 GPU 开发 → 胶片风格。**明确记录"200 MP 对第三方只开 12.5 MP binned RAW"** |
| `dwaipayanray95/vesper-cine` | 2 | RAW10 → Vulkan → 10 bit；Pixel 10 上 1080p 每帧 29–32 ms；**热预报逐级降级**的工程范式 |
| `Kp4103/lumenraw-camera` | 0 | Kotlin + Camera2 + NDK 的 HDR+ 风格实现 |
| `benbaobaoshigemi/OffLineM9` | 40 | 小米 17 Ultra 徕卡 M9 离线复刻；**证明厂商 DNG 已含 MFNR/AI 降噪**，且厂商风格本质是 17³ 3D LUT |
| `ColdP/HyperChanger`、`wuchuzuoyou/MiCamOS1Revive-aurora`、`Picters/*-Unlocker` | 318 / 6 / 1 | 厂商相机解锁类，**需 root / LSPosed，不可上架** |
| `Jip-Hop/xiaomi-14-ultra-raw-video-mod-guide` | 9 | 用 MotionCam 在小米 14 Ultra 上拍 RAW 视频的实践 |

应用内拍摄 RAW 与厂商计算摄影的边界，见 §4.4.4 与 §4.4.6。

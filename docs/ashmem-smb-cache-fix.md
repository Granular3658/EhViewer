# EhViewer 远程 SMB 动图缓存修复方案（ashmem + 有界 LRU）

> 整理自一次完整的 CI 调试 + 运行时诊断过程，目的是把"为什么这么改、最终架构是什么、怎么验证"固化下来，避免对话被清理后丢失上下文。

---

## 1. 背景与目标

**问题**：远程 SMB 上的动图（animated WebP / GIF）在画廊里解码时，会爆 `OOM` / `NPE` / `EBADF`，最典型的两个运行时报错是：

- `java.io.IOException: cannot mmap zero-length source (pipe?)`
- `java.io.IOException: Channel not open for writing - cannot extend file to required size`

**根因（原始设计）**：Coil 解码动图时拿不到一个**可 mmap 的真实 fd**。

- 旧路径用一次性 `pipe` 把 SMB 流喂给解码器 —— pipe **不能 mmap**，所以 animated WebP 解码器（必须 mmap）直接失败；
- 为了绕过 pipe，曾给每个图片在 **JVM 堆里**拷一份 direct buffer —— 这正是 **OOM 的根源**（翻大图库时堆压力爆掉）。

**目标方案**：

1. Rust 侧把远程 SMB 文件**整读进一块 ashmem 共享内存**（RAM，不落盘、不磨损闪存）；
2. `dup` 出一个**真实、可 mmap 的 fd** 交给 Coil，做到**零拷贝 mmap**（底层就是共享内存本身，不占 JVM 堆）；
3. 用**有界 LRU（256MiB）**约束驻留内存；超阈值只回收"已划走、不再被引用"的旧页面；
4. ashmem 不可用时（极端情况）回退到 pipe 流式路径。

---

## 2. 环境 / 分支 / CI 关键事实

| 项 | 值 |
|---|---|
| 仓库 | `Granular3658/EhViewer`（本地 `/workspace/EhViewer_src`） |
| 用户测试包 | **default** 包（`android-26` feature 启用） |
| 用户系统 | LOS 15（Android 8.1 / API 27） |
| `minSdk` | 26；ashmem 代码用 `#[cfg(feature = "android-26")]` 包裹 |
| CI 触发分支 | **`fix-smb-ashmem`（无斜杠）** ← 实际跑构建的分支 |
| CI 不触发 | `fix/smb-ashmem-cache`（含 `/`）；`ci.yml` 里 `branches: ['*']` 不匹配含 `/` 的分支 |
| 代码风格 | 项目用 **ktlint 1.8.0**（不是 ktfmt）；Rust 用 `cargo fmt` + `clippy --all-features -D warnings` |
| 本地 ktlint | 项目 spotless 需 Java 21，沙箱只有 Java 20，故用 standalone ktlint jar 校验 |

> ⚠️ **推送约定**：要让 CI 跑，必须推到 `fix-smb-ashmem`；推 `fix/smb-ashmem-cache` 只保持源码一致（用户本地拉这个分支拿代码）。两个分支最终都指向同一 commit。

---

## 3. 迭代记录（根因演进）

| 阶段 | 提交 | 现象 / 问题 | 处置 |
|---|---|---|---|
| 初版 CI 红 | `b42a0766d` 等 | Rust `libc` 未声明、Kotlin pipe/mmap bug | 修依赖与 Kotlin |
| `--locked` | `e4e8f7b30` | Cargo.lock 缺 `libc 0.2.189` | 手动补进 `ehviewer_rust` deps |
| E0499 ×2 | `1f925d77e`/`a180062ba` | 对 `guard` 的双重可变借用 | 缩小借用作用域 |
| fmt/clippy/ktlint | `3d627af7e` | 格式与告警 | 全部通过，CI 绿 |
| 运行时① | `2a4d5fe5ae` | `cannot mmap zero-length source (pipe?)` | 加诊断日志，发现 ashmem 主路径其实**已成功**（fd/size 都对），崩在解码器 `channel.size()==0` |
| 修复 v1 | `2e2292a0bc` | `SmbProvider` 把真实 size 透传 `afd.length`，解码器优先用 `afd.length` | **default 构建通过**，但运行时报 `Channel not open for writing` |
| 修复 v2（拷贝） | `33d8ecbea2` | 解码器把 ashmem 拷进 direct buffer | 违背"别占 JVM 堆"初衷，**default 构建也失败** |
| 修复 v3（原生 mmap） | `4481ce2448` | Rust `smbMmapReadOnly` 用真实 size 原生 mmap 成 direct `ByteBuffer`（零拷贝）；`dispose` 时 `smbMunmap` | **default 构建失败**：`AnimatedWebPDecoder.kt:65` 调用 `.channel.mapReadOnly()` 类型不匹配 |
| **修复 v4（最终）** | `07e88be0d4` | 第 65 行管道 fallback 改返回 `null`（让 Coil 走默认回落器） | **CI 全绿**，用户 500+ 张 10MB+ webp 压测无报错无崩溃 |

### 两个运行时报错的根因链

1. **`cannot mmap zero-length source (pipe?)`**
   ashmem 区域本身是满尺寸的，但 LOS 15 / Android 8.1 上该 ashmem fd 的 `fstat` 对**大文件返回 0**。解码器用 `channel.size()` 取大小时拿到 0 → 误判成零长度 pipe。
   （小缩略图 ~20KB 正常、16MB webp 失败，正是这个"大文件 fstat 回归 0"的阈值表现。）

2. **`Channel not open for writing - cannot extend file to required size`**
   上一版把真实 size 传给 `FileChannel.map()`，但框架 `OffsetCorrectFileChannel.map()` 内部会**再用 fstat 重新核对**大小——又拿到 0，于是认为要把只读文件"扩展"到 16MB → 只读 fd 无法扩展而抛错。

3. **为什么 GIF 不受影响**：GIF 走 `InputStream` 流式解码，**根本不调** `channel.size()` / `mmap`，所以 ashmem fd 的 fstat 怪异对它毫无影响。9MB GIF 能正常经 ashmem 解码，反而印证了"ashmem 本身对大文件没问题，问题只在 mmap 路径对 fstat 的依赖"。

---

## 4. 最终架构

### 数据流（一次画廊 webp 解码）

```
SMB 服务器
  │ smb::stat / smb::open / smb::read(带 3 次重试)
  ▼
[Rust] open_ashmem(target)
  ├─ 整文件读入 SharedMemory（ashmem，RAM）
  ├─ mmap(PROT_READ|PROT_WRITE) 拷贝 → set_prot(PROT_READ)
  ├─ dup_fd → 真实 fd  +  pins += 1（引用计数）
  ▼
[Kotlin] SmbProvider.openFile → adoptFd(fd)，close 时 releaseAshmem(key)
          openAssetFile → 用真实 size 构造 AssetFileDescriptor(length)
  ▼
[Coil] ContentMetadata → AnimatedWebPDecoder.toByteBufferOrNull()
  ├─ afd.length > 0（ashmem 路径）：
  │     smbMmapReadOnly(fd, size)  ── 原生 mmap，绕过 fstat，零拷贝
  │     → direct ByteBuffer（底层即 ashmem 共享内存，不占 JVM 堆）
  │     release = { smbMunmap(buffer) }
  └─ afd.length <= 0（管道 fallback）：返回 null，让 Coil 用其它解码器
  ▼
[Native] AnimatedWebPDrawable 用 GetDirectBufferAddress 读 buffer 播放
  dispose() → nativeDestroyDecoder + smbMunmap(buffer)（解映射）
```

### 关键文件与职责

| 文件 | 职责 |
|---|---|
| `rust/src/smb_cache.rs` | `open_ashmem` / `release_ashmem` / `evict`；`Entry{pins}`；仅回收 `pins==0`；`evict()` 仅在下一次 `open_ashmem` 时触发；`MAX_BYTES=256MiB` |
| `rust/src/ffi/smb.rs` | JNI：`smbOpenAshmem` / `smbReleaseAshmem` / `smbMmapReadOnly` / `smbMunmap` |
| `kotlin/.../jni/Smb.kt` | 对应 `external fun` 声明 |
| `kotlin/.../smb/SmbRepository.kt` | `openAshmem(location)` / `releaseAshmem(key)`；`SmbAshmemHandle(fd,size,key)` |
| `kotlin/.../smb/SmbProvider.kt` | `openFile` 优先 ashmem（返回真实 size），`openAssetFile` 把真实 size 透传为 `AssetFileDescriptor.length`；管道 fallback 返回 `-1L` length |
| `kotlin/.../coil/AnimatedWebPDecoder.kt` | `ContentMetadata` 分支 `afd.length>0` 走原生 mmap；否则 `null` 回落；本地文件走 `mapReadOnly()` |
| `kotlin/.../coil/AnimatedWebPDrawable.kt` | 构造器接 `release` 回调，`dispose()` 时 `smbMunmap` 解映射 |

### 为什么是安全的（竞态分析）

- **零拷贝**：`ByteBuffer` 底层就是 ashmem 共享内存，不经过 JVM 堆 → 不会回到原 OOM 路径。
- **绕开 fstat 谎言**：原生 `mmap` 直接传 Rust 已知真实 `size`，不依赖 fd 的 `fstat`。
- **无 SIGBUS 窗口**：
  1. 区域受 `pins` 引用计数保护，`release_ashmem` 只在 Coil 关闭 source fd 时调用；
  2. 只要 drawable 还在播放、source 还被持有，PFD 不关 → `pins > 0` → `evict()` 永远动不了它；
  3. `evict()` 仅在下一次 `open_ashmem` 触发，且只回收 `pins==0` 的项 —— 即便 `pins` 先归零、mmap 还映射着，底层内存也延迟到下次打开才真正释放，而 `dispose()`（内部 `smbMunmap`）在此之前已解映射。
- **实测验证**：500+ 张、平均 10MB+ 的 webp 来回翻、长时间使用，UI 无报错无崩溃。

---

## 5. 分支 / CI / 产物

- **最终 commit**：`07e88be0d4`（message：`fix(coil): drop broken pipe-fallback mmap call in AnimatedWebPDecoder`）
- **CI run**：`37887025690` → **success**（default / check / marshmallow 全过）
- **用户用产物**：`default-arm64-v8a-07e88be0d4...`
- **下载页**：https://github.com/Granular3658/EhViewer/actions/runs/37887025690

---

## 6. 验证步骤（回归用）

1. 从 run `37887025690` 下载 **`default-arm64-v8a`** APK 安装；
2. 点开之前爆错的 **16MB 画廊 webp 动图**；
3. 预期：两类崩溃（`cannot mmap zero-length source` / `Channel not open for writing`）均消失，动图正常播放；
4. 同时大 GIF（9MB）依旧正常（流式解码，不受影响）；
5. 可选压测：大图库来回翻，观察是否出现 OOM / SIGBUS / 解码失败。

---

## 7. 已知边界 / 未来可扩展

- **管道 fallback 不再 mmap**：`afd.length<=0`（即 ashmem 不可用、走了 pipe）时，animated WebP 解码器返回 `null` 让 Coil 回落。若某天希望 pipe 也支持动图，需要另写流式解码器（非 mmap）。
- **256MiB 上限是特性不是 bug**：只约束"已划走、不再 pin 的旧页面"的残留内存；活跃页面永远在安全区。
- **之前担心的"超 256MiB 回收竞态"经分析不存在**（见 §4 竞态分析），已实测验证，无需额外加 `releaseAshmem(key)` 精确存活期管控。

---

## 8. 持久化说明（对话之外如何保存本文）

- **`/workspace` 本地文件（本文件）**：对话清理后仍可在此找到；但随沙箱生命周期，极端情况下沙箱整体清空会丢。
- **Git 提交到仓库**：把本文 `git add` 进 `EhViewer_src` 的 `docs/` 并提交，可随代码永久留在 GitHub 历史（注意会污染源码树，建议放 `docs/` 目录）。
- **WorkBuddy 资料库（Library / 云文档）**：跨设备、不受沙箱清理影响，最适合做"长期备忘"。需要的话可以把本文同步到资料库，或用 `/library` 技能管理。

> 一句话：要"这次能找到"用 `/workspace` 即可；要"换设备/沙箱清了也还在"建议再存一份到资料库或提交进 Git。

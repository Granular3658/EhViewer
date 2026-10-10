# EhViewer 远程 SMB 动图缓存修复方案（memfd + 有界 LRU）

> 整理自一次完整的 CI 调试 + 运行时诊断过程，目的是把"为什么这么改、最终架构是什么、怎么验证"固化下来。
>
> 缓存后端最初是 ashmem，后来迁到 **memfd**（依据见 §2 的设备探测、结论见 §3）。本文记录 memfd 版本的设计，并说明 ashmem 时代若干结论（尤其是"ashmem 的 `fstat` 恒为 0 是根因"）为何被修正。
>
> ⚠️ **分支说明**：早期 ashmem 方案的完整历史已归档为 `archive/fix/smb-ashmem-cache`（11 个有序提交），更早的原始历史在 `archive/fix-smb-ashmem-raw`。现行方案是 `feat/smb-memfd`（基于 `main`，不依赖归档分支）。

---

## 1. 背景与目标

**问题**：远程 SMB 上的动图（animated WebP / GIF）在画廊里解码时会爆 `OOM` / `NPE` / `EBADF`，最典型的两个运行时报错是：

- `java.io.IOException: cannot mmap zero-length source (pipe?)`
- `java.io.IOException: Channel not open for writing - cannot extend file to required size`

**根因（原始设计）**：Coil 解码动图时拿不到一个**可 mmap 的真实 fd**。

- 旧路径用一次性 `pipe` 把 SMB 流喂给解码器 —— pipe **不能 mmap**，所以 animated WebP 解码器（必须 mmap）直接失败；
- 为了绕过 pipe，曾给每张图在 **JVM 堆里**拷一份 direct buffer —— 这正是 **OOM 的根源**（翻大图库时堆压力爆掉）。

**目标方案**：

1. Rust 侧把远程 SMB 文件**整读进一块匿名内存**（`memfd_create(2)`，RAM，不落盘、不磨损闪存）；
2. `dup` 出一个**真实、可 mmap 的 fd** 交给 Coil，做到**零拷贝 mmap**（底层就是那块内存本身，不占 JVM 堆）；
3. 用**有界 LRU（256MiB）**约束驻留内存；超阈值只回收"已划走、不再被引用"的旧页面；
4. 缓存不可用时回退到 pipe 流式路径。

---

## 2. 环境 / 分支 / CI 关键事实

| 项 | 值 |
|---|---|
| 仓库 | `Granular3658/EhViewer` |
| 测试设备 | LOS 18.1（Android 11 / Linux **4.4.302**）+ 另一台（Android 15 / Linux **4.19.311**），均 `arm64-v8a`、均 `u:r:untrusted_app` |
| `minSdk` | 26；缓存代码用 `#[cfg(feature = "android-26")]` 包裹（feature 定义见 `rust/Cargo.toml`） |
| CI 触发 | `ci.yml` 的 `branches: ['*']` **不匹配含 `/` 的分支**，所以 push 本仓库的 `feat/...` / `archive/...` 都不会自动跑；用 `gh workflow run ci.yml --ref <branch>` 手动触发 |
| 代码风格 | **ktlint 1.8.0**（不是 ktfmt），经 spotless；Rust 用 `cargo fmt` + `clippy --all-features -- -D warnings` |
| `check` job | rustfmt → clippy → `:build-logic:convention:check` → spotlessCheck → lintMarshmallowRelease |

### 设备探测结论（minimal probe APK 实测）

| 探测项 | Linux 4.19（A15） | Linux 4.4（A11） |
|---|---|---|
| `memfd_create(MFD_CLOEXEC\|MFD_ALLOW_SEALING)` | ✅ fd 正常 | ✅ fd 正常 |
| `ftruncate` 后 `fstat` 的 `st_size` | ✅ 1048576（真实大小） | ✅ 1048576 |
| `F_ADD_SEALS(F_SEAL_FUTURE_WRITE)` | ✅ `r=0` | ❌ `r=-1 errno=22 (EINVAL)` |
| `F_ADD_SEALS(F_SEAL_SHRINK\|F_SEAL_GROW)` | ✅ | ✅ |
| `open("/dev/ashmem")` | ❌ `errno=13`（SELinux `avc: denied`） | ❌ `errno=13`（同上） |
| `ASharedMemory_create()` 返回的是不是 ashmem | 是（`readlink` → `/dev/ashmem...`） | 是 |

> 关键推论：**两台上 `/dev/ashmem` 都被 SELinux 拒绝**，ashmem 对 app 已是死路；memfd 在两边都可用，且 `fstat` 报告真实大小。→ 这是迁移到 memfd 的直接依据。

---

## 3. 迭代记录（根因演进）

| 阶段 | 现象 / 问题 | 处置 |
|---|---|---|
| 初版 CI 红 | Rust `libc` 未声明、Kotlin pipe/mmap bug | 修依赖与 Kotlin |
| `--locked` | `Cargo.lock` 缺 `libc` | 补进 `ehviewer_rust` deps |
| E0499 ×2 | 对 `guard` 的双重可变借用 | 缩小借用作用域 |
| fmt/clippy/ktlint | 格式与告警 | 全部通过，CI 绿 |
| 运行时① | `cannot mmap zero-length source (pipe?)` | 加诊断日志，发现缓存主路径其实**已成功**（fd/size 都对），崩在解码器 `channel.size()==0` |
| 修复 v1 | ashmem 长度透传 | `SmbProvider` 把真实 size 透传 `afd.length`，解码器优先用它 → 又报 `Channel not open for writing` |
| 修复 v2（拷贝） | 解码器把内容拷进 direct buffer | 违背"别占 JVM 堆"初衷，default 构建也失败 |
| 修复 v3（原生 mmap） | Rust `smbMmapReadOnly` 用真实 size 原生 mmap 成 direct `ByteBuffer`（零拷贝）；`dispose` 时 `smbMunmap` | 类型不匹配，失败 |
| **修复 v4** | 第 65 行管道 fallback 改返回 `null`（让 Coil 走默认回落器） | **CI 全绿**，500+ 张 10MB+ webp 压测无报错无崩溃 |
| **Bug B 修复** | 往回翻部分 WebP 变成静止首帧，**仅 Linux 4.4、概率性（约 20%）** | `fix(coil): detect animated WebP from the mmap buffer`：以显式长度 mmap 出的缓冲为权威判据，绕开 fd 读取路径 |
| **memfd 迁移** | ashmem 被 SELinux 拒绝、内核 5.18 已移除、Android 17 要求 `memfd_file` 类 | 缓存后端换成 `memfd_create` + `ftruncate`，其余架构不变 |

### 两个运行时报错的根因链（ashmem 时代）

1. **`cannot mmap zero-length source (pipe?)`**
   ashmem 区域本身是满尺寸的，但 **ashmem fd 的 `fstat` 恒为 0**（Android 的 ashmem 驱动从不填 `i_size`）。解码器用 `channel.size()` 取大小拿到 0 → 误判成零长度 pipe。
   （早期曾误写成"LOS 15 / Android 8.1 / 大文件 fstat 回归 0"——这是误判：`fstat=0` 与文件大小、系统版本无关，是 ashmem 的普遍行为。）

2. **`Channel not open for writing - cannot extend file to required size`**
   把真实 size 传给 `FileChannel.map()` 后，框架 `OffsetCorrectFileChannel.map()` 内部会**再用 fstat 重新核对**大小——又拿到 0，于是认为要把只读文件"扩展"到 16MB → 只读 fd 无法扩展而抛错。

3. **为什么 GIF 不受影响**：GIF 走 `InputStream` 流式解码，**根本不调** `channel.size()` / `mmap`，所以 ashmem fd 的 `fstat=0` 对它毫无影响。

### Bug B 的根因（已定位并修复）

**不是内核问题。** 迁到 memfd 后 `fstat` 已经正确，但 Android 11 设备上预检仍然大量失败，所以先前"ashmem 的 `fstat=0`"的解释被证伪。实测定位的根因见 §4「描述符交接」：

> 给消费者的 fd 是用 `dup(2)` 发的，而 `dup` **共享文件位置**；Android 15 之前 `AssetFileDescriptor.AutoCloseInputStream` 是用 `read(2)` 读的，正好依赖那个共享位置。

| AOSP 版本 | `AutoCloseInputStream` 实现 | 是否依赖文件位置 |
|---|---|---|
| Android **11 / 12 / 13 / 14** | `super.skip(startOffset)` + `super.read(...)`（即 `FileInputStream.read(2)`） | **是** ❌ |
| Android **15** | `SeekableAutoCloseInputStream` → **`Os.pread`** | 否 ✅ |

链路：第一个消费者读走一个 8 KiB segment → 共享位置前进 → 之后每次解码同一个文件都从错位处读 → 前 21 字节不是 RIFF 魔数 → 预检判"非动图" → 回落静态解码器只渲首帧。

**为什么两台设备表现不同**：变量不是内核版本，而是 **Android 版本**（11 vs 15）。两台测试机恰好"Android 11 ↔ 内核 4.4"、"Android 15 ↔ 内核 4.19"一起出现，把 Android 版本的差异误判成了内核差异。

**为什么是概率性的**：Coil 的静态解码器在读之前会 `Os.lseek(fd, startOffset, SEEK_SET)`，把共享位置重置回 0；所以一旦回落到静态路径，下一次解码又能成功——表现为"往回翻时偶发静图"。

**实测证据**（Android 11 设备）：

- `pread0`（偏移 0 的 `pread`）和 `mmap0`（同一 fd 的 mmap）**都是正确的 `RIFF…WEBP…VP8X…` 头** → **fd 和内核都正常**；
- 而流读到的 `head` 是垃圾，且 `head == pread(pos − 8192)`（两组独立验证）；
- 同一文件反复解码时 `pos` 每次 **+8192**（正好一个 okio segment）。

**修复**：把 `dup` 换成 `open("/proc/self/fd/N", O_RDONLY)`（见 §4）。修复后两台设备长时间浏览大动图画廊，预检失败**归零**。

---

## 4. 最终架构

### 数据流（一次画廊 webp 解码）

```
SMB 服务器
  │ smb::stat / smb::open / smb::read(带 3 次重试)
  ▼
[Rust] smb_cache::open_memfd(target)
  ├─ memfd_create(MFD_CLOEXEC|MFD_ALLOW_SEALING)  →  匿名内存文件
  ├─ ftruncate(size)  →  fstat 从此报告真实大小
  ├─ mmap(PROT_READ|PROT_WRITE) 写入全部字节 → munmap
  ├─ seal_read_only()：F_SEAL_FUTURE_WRITE，失败(4.4 EINVAL)则降级 F_SEAL_SHRINK|F_SEAL_GROW
  ├─ dup_fd → 真实 fd  +  pins += 1（引用计数）
  ▼
[Kotlin] SmbProvider.openAssetFile → AssetFileDescriptor(fd, 0, size)   ← 只有图片走这里
         SmbProvider.openFile       → 纯 fd，length = -1              ← 其余读走 pipe
  ▼
[Coil] ContentMetadata → AnimatedWebPDecoder.toByteBufferOrNull()
  ├─ afd.length > 0（内存缓存路径）：
  │     smbMmapReadOnly(fd, size)  ── 原生 mmap，零拷贝
  │     → direct ByteBuffer（底层即 memfd 内存，不占 JVM 堆）
  │     release = { smbMunmap(buffer) }
  └─ afd.length <= 0（管道 fallback）：返回 null，让 Coil 用其它解码器
  ▼
[Native] AnimatedWebPDrawable 用 GetDirectBufferAddress 读 buffer 播放
  dispose() → nativeDestroyDecoder + smbMunmap(buffer)（解映射）
```

### 关键文件与职责

| 文件 | 职责 |
|---|---|
| `rust/src/smb_cache.rs` | `open_memfd` / `release_memfd` / `evict`；`Entry{pins}`；仅回收 `pins==0`；`evict()` 仅在下一次 `open_memfd` 时触发；`MAX_BYTES=256MiB` |
| `rust/src/ffi/smb.rs` | JNI：`smbOpenMemfd` / `smbReleaseMemfd` / `smbMmapReadOnly` / `smbMunmap` / `smbSetCacheLimitMb` |
| `kotlin/.../jni/Smb.kt` | 对应 `external fun` 声明 |
| `kotlin/.../smb/SmbRepository.kt` | `openMemfd(location)` / `releaseMemfd(key)`；`SmbMemfdHandle(fd,size,key)` |
| `kotlin/.../smb/SmbProvider.kt` | 按内容类型分流（见下）；图片路径把真实 size 透传为 `AssetFileDescriptor.length`，其余返回 `-1L` |
| `kotlin/.../coil/AnimatedWebPDecoder.kt` | `ContentMetadata` 分支 `afd.length>0` 走原生 mmap；否则 `null` 回落；本地文件走 `mapReadOnly()` |
| `kotlin/.../coil/AnimatedWebPDrawable.kt` | 构造器接 `release` 回调，`dispose()` 时 `smbMunmap` 解映射 |

### 为什么"非图片读必须走 pipe"——这是平台要求，不是 workaround

`SmbProvider.openAssetFile` 里按扩展名分流（`isSmbImagePath`）：图片用内存缓存 + 声明长度，其余（`.ehviewer` / `ComicInfo.xml` 等）走 pipe + 长度 `-1`。

**不能统一走内存缓存**，因为 AOSP `ContentResolver.openFileDescriptor`：

```java
if (afd.getDeclaredLength() < 0) {
    // This is a full file!
    return afd.getParcelFileDescriptor();
}
// Client can't handle a sub-section of a file, so close what we got and bail with an error
throw new FileNotFoundException("Not a whole file");
```

`AndroidFileSystem.openFileDescriptor` 对**每个** SMB 读都用 `contentResolver.openFileDescriptor(uri, "r")`。一旦返回的 `AssetFileDescriptor` 带 `declaredLength >= 0`，这里直接抛 `FileNotFoundException("Not a whole file")` —— 实测后果是**"恢复 SMB 下载"永远报"没找到可恢复的条目"**（`readCompatFromPath` 用 `runCatching` 静默吞掉了异常）。

所以：**只有 mmap 路径（Coil 走 `openAssetFileDescriptor`）才需要声明长度；整文件消费者必须拿到 `-1`。**

### 为什么用原生 mmap 而不是 `FileChannel.map()`

`AssetFileDescriptor` 上唯一能拿到 `FileChannel` 的途径是 `createInputStream()`，而它的 `AutoCloseInputStream` **拥有并在 `close()` 时关闭 provider 的 `ParcelFileDescriptor`**——那会在 Coil 仍需要该 source 时把它拆掉（例如解码器工厂返回 `null`、Coil 回落静态解码器时）。原生 `smbMmapReadOnly` 还附带一个好处：`dispose()` 时能**显式 `munmap`**，而不是等 GC。

### 描述符交接：为什么用 `open` 而不是 `dup`

每个消费者拿到的是**重新打开**的描述符，不是 `dup` 出来的：

```rust
let path = CString::new(format!("/proc/self/fd/{}", mem.fd.as_raw_fd()))?;
let fd = unsafe { libc::open(path.as_ptr(), libc::O_RDONLY | libc::O_CLOEXEC) };
```

`dup(2)` 创建的是**同一个 open file description 的第二个引用**——两者**共享文件位置**。`open("/proc/self/fd/N")` 创建的是**新的 open file description**：位置从 0 开始，各消费者互不影响。

这在 Android 15 之前是**必须**的：那时 `AutoCloseDescriptor` 的流用 `read(2)` 读，而 `read(2)` 依赖文件位置。用 `dup` 的话，第一个消费者一读就把位置推过文件头，后续每次解码都读错位（完整分析见 §3）。

顺带的好处：`O_RDONLY` 让描述符**只读**。`F_SEAL_FUTURE_WRITE` 在 Linux 4.4 上返回 `EINVAL`，seal 做不到这一点，`O_RDONLY` 补上了。

如果 `open` 失败（例如某个 SELinux 域不允许），代码回退到 `dup` + `lseek(fd, 0, SEEK_SET)` 并打一条 warn。回退不是无竞态（并发消费者可能在 rewind 和 read 之间移动位置），但比直接失败好——失败会让调用方掉到 pipe，而 pipe 不能 mmap，动图解码器就彻底没得用。这个回退也和平台自己的静态解码器做法一致。

### 为什么是安全的（竞态分析）

- **零拷贝**：`ByteBuffer` 底层就是 memfd 内存，不经过 JVM 堆 → 不会回到原 OOM 路径。
- **无 SIGBUS 窗口**：
  1. 区域受 `pins` 引用计数保护，`release_memfd` 只在 Coil 关闭 source fd 时调用；
  2. 只要 drawable 还在播放、source 还被持有，PFD 不关 → `pins > 0` → `evict()` 永远动不了它；
  3. `evict()` 仅在下一次 `open_memfd` 触发，且只回收 `pins==0` 的项 —— 即便 `pins` 先归零、mmap 还映射着，底层内存也延迟到下次打开才真正释放，而 `dispose()`（内部 `smbMunmap`）在此之前已解映射。
  4. `F_SEAL_SHRINK|F_SEAL_GROW` 在任何 ≥3.17 的内核上都可用，保证区域不会被截断/扩展（SIGBUS 的根本防线）。
- **实测验证**：500+ 张、平均 10MB+ 的 webp 来回翻、长时间使用，UI 无报错无崩溃。

---

## 5. 分支 / CI / 产物

- `feat/smb-memfd` —— 现行方案（基于 `main`）：SMB 网络存储 + 多下载目录 + 有界内存缓存（memfd 后端）
- `archive/fix/smb-ashmem-cache` —— 早期 ashmem 方案的完整修复（11 个有序提交），**已被 memfd 取代，仅存档**
- `archive/fix-smb-ashmem-raw` —— ashmem 方案整理前的原始历史（含调试提交）
- **CI**：均需手动触发（见 §2）；check / default / marshmallow 三个 job 全绿才继续
- **用户测试包**：`default-arm64-v8a`（`android-26` feature 启用）
- **下载页**：https://github.com/Granular3658/EhViewer/actions

---

## 6. 验证步骤（回归用）

1. 下载 `default-arm64-v8a` APK 安装；
2. 打开之前爆错的 **16MB 画廊 webp 动图**；预期两类崩溃均消失、动图正常播放；
3. 大 GIF（9MB）依旧正常（流式解码，不受影响）；
4. **恢复 SMB 下载**：应能找到未索引的条目并恢复（这是"非图片走 pipe"那条路由的回归点）；
5. 可选压测：大图库来回翻，观察是否出现 OOM / SIGBUS / 解码失败；
6. 回归观察：往回翻动图时**不应**再出现"动图变静止首帧"（Bug B，见 §3）。

---

## 7. 已知边界 / 未决问题

- **低版本设备未实测**：`minSdk = 26`。`memfd_create` 走的是裸 `libc::syscall(libc::SYS_memfd_create, ...)`（不依赖 API 30 才有的 bionic 包装），内核 ≥3.17 即可，理论可行，但没有 Android 8/9/10 真机验证过。
- **长时 soak 未做**：只做过"大图库来回翻很久"，没有做长时间 / 极限内存压测。
- **管道 fallback 不支持动图**：`afd.length<=0` 时 animated WebP 解码器返回 `null` 让 Coil 回落。若希望 pipe 也支持动图，需要另写流式解码器（非 mmap）。
- **256MiB 上限是特性不是 bug**：只约束"已划走、不再 pin 的旧页面"的残留内存；活跃页面永远在安全区。
- **mmap buffer 复核（`fix(coil): detect animated WebP from the mmap buffer`）现在是纯安全网**：Bug B 修掉后它不再需要兜底，但保留——预检若因别的原因偶发误判，它会兜住而不是变成静图。

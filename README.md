# DSH Portable

> **双架构通用版（本仓库）**：一个 APK 同时打包 **arm64-v8a（64 位）** 与
> **armeabi-v7a（32 位 ARMv7）**。本仓库是在 [cyf112233/DSH-Portable](https://github.com/cyf112233/DSH-Portable)
> 基础上新增 32 位支持的社区构建，遵循原项目 **GPL-3.0-or-later** 协议开源，全部上游版权与许可声明保留不变。
>
> - **64 位手机（arm64-v8a）**：DeepSeek AI 网页服务 + Debian 终端，全部功能与上游一致。
> - **32 位老手机（armeabi-v7a）**：完整可用的 **Debian 13 Linux 终端 + Node.js 22 / npm**（bash、apt 等正常）。
>   dsh 的 AI 服务仅支持 64 位（原因见下文[「关于 32 位」](#关于-32-位-armv7)），32 位机上 App 会直接引导进入终端。

**在 Android 手机上本地运行完整的 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)** ——
不用 root，不用 Termux，装一个 APK 就有。

APK 内置 Debian arm64 根文件系统与 Node.js，用 [PRoot](https://proot-me.github.io/)
以普通应用权限启动，界面由 WebView 访问 `127.0.0.1` 上的**标准 `dsh web` 服务**——
不是仿制界面，跑的就是真正的 dsh web。

```
Android App ──► PRoot ──► Debian + Node.js ──► dsh --profile web
                                                    │
       WebView ◄── http://127.0.0.1:<port>/?token=… ┘
```

## 特性

- **开箱即用**：Debian 13 + Node.js 22 + dsh 全部内置，首次启动自动解压，无需联网装环境
- **无需 root**：PRoot 用户态沙箱，普通应用权限即可
- **竖屏适配**：内置 [`dsh-web-mobile`](https://github.com/mexiaosqwq/dsh-web-mobile)，
  窄屏抽屉布局、触屏可用（走 dsh 自己的插件机制，不改它的源码）
- **目录挂载**：把手机目录挂进 Debian，**双向实时读写**（绑定挂载，非复制）
- **真 Debian 终端**：顶栏「终端」是一个真正的 PTY 会话（自写 VT100 子集 + 4 个系统调用的 native PTY），
  `apt`、`vim`、Ctrl-C 都正常，不是日志面板
- **后台保活**：前台服务 + 常驻通知，可开关
- **端口自适应**：默认端口被占用时自动顺延
- **离线可用**：除模型 API 外不依赖网络

## 要求

| 项目 | 要求 |
| --- | --- |
| Android | 7.0 (API 24) 及以上 |
| 架构 | **arm64-v8a**（AI 服务 + 终端）与 **armeabi-v7a**（仅终端），同一个 APK |
| 存储 | 64 位约 520 MB；32 位约 300 MB（系统按自身 ABI 只解压对应镜像） |

## 安装

从 [Releases](../../releases) 下载 APK 后 `adb install -r dsh-portable.apk`，
或直接拷进手机点击安装（自签名证书，系统会提示"未知来源"）。

## 使用

1. 首次启动解压 Debian 环境，约 1–2 分钟，有进度与实时日志。
2. 界面自动加载；顶栏右侧「控制台」可看服务原始输出。
3. 在 DSH 的 **设置 → Models** 填入 DeepSeek API Key（存在设备本地）。
4. **点击顶部「DSH Portable」标题**进入设置（首次启动会提示一次，可勾选不再提示）：

   | 设置项 | 说明 |
   | --- | --- |
   | 目录挂载 | 手机共享存储 ↔ `/root/xxx`，双向实时；增删后提示重启生效 |
   | 服务端口 | 仅监听 `127.0.0.1`，被占用时自动顺延 |
   | 后台保活 | 常驻通知栏防系统回收 |
   | 重新安装环境 | 重新解压 rootfs；**`/root` 下的用户数据（API Key、会话、工作区）会保留** |
   | 关于与开源协议 | 组件版本与协议全文 |

## 关于 32 位（ARMv7）

**32 位镜像能做什么**：完整的 Debian 13（trixie, armhf）终端——`bash`、`apt`、
`vim`、联网安装软件包，以及官方 **Node.js 22（linux-armv7l）+ npm 10**，可正常运行
纯 JS / N-API 中自带 armv7 预编译的程序。首次启动自动解压，PTY、挂载、后台保活等
终端相关能力与 64 位一致。

**为什么 32 位没有 AI 服务**：dsh 唯一的必需原生模块
[`node-addon-require-builtin`](https://github.com/deepseek-ai/dsh-node-addon-require-builtin)
官方只发布 **x64 / arm64** 预编译包。该模块的工作原理是直接解析 V8 私有机器码与内存布局，
每个架构需要单独的 getter 解析器；源码里不存在 `linux_glibc_arm` 解析器，在 armv7 上会
按设计「fail closed」拒绝加载（见其 `docs/support-matrix.md`）。这不是权限或打包问题，
而是上游不支持、且无法在不逆向 32 位 V8 内部 ABI 的前提下可靠补齐，因此不做实验性塞入。
终端功能不依赖该模块，故在 32 位上独立、完整可用。

32 位原生库（`proot` / `libtalloc` / `libandroid-shmem`）取自 Termux 官方 **arm** 软件包；
PTY JNI 库 `libdshpty.so` 由官方 NDK 对 `app/jni/pty.c` 交叉编译而来。架构选择在
`app/src/.../core/Abi.java`：Android 自动按设备 ABI 抽取对应的 `lib/<abi>` 目录，
App 在首次解压时按 ABI 选择 `rootfs/debian-arm64.tar.gz` 或 `rootfs/debian-arm.tar.gz`。

## 自行构建

双架构 APK 可在普通 **x86_64 Linux** 上交叉构建（不再要求 aarch64 主机）。需要
`debootstrap`、`qemu-user-static`、一个支持 statx 的 `proot`（≥ 5.4；Ubuntu 22.04 自带的
5.1 不行，可用 Ubuntu 24.04 或从源码编译 `proot-me/proot`）、`aapt`、`zipalign`、
`apksigner`、`zip`、`python3`、JDK 8（`javac`）以及一份 Android **NDK**（r26 测试通过，
用于编译两种 ABI 的 `libdshpty.so`）和 SDK `platforms/android-34` + `build-tools/34.0.0`。

```bash
# 1) 两个 rootfs（sudo；arm64 沿用上游脚本，armhf 用新增脚本）
sudo ./scripts/build-rootfs.sh          # -> app/payload/rootfs/debian-arm64.tar.gz（含 dsh）
sudo ./scripts/build-rootfs-armhf.sh    # -> app/payload/rootfs/debian-arm.tar.gz（终端 + Node）

# 2) 一个双 ABI APK（自动用 NDK 编译 arm64 + armv7 的 PTY 库，打包两套 lib 与两个 rootfs）
ANDROID_HOME=/path/to/sdk NDK_DIR=/path/to/android-ndk-r26d ./build.sh
#    -> build/dsh-portable.apk
```

`scripts/patch-dsh.py` 在生成 arm64 rootfs 时自动给 dsh 打两处 Android 适配补丁；
`build.sh` 不用 Gradle：Debian 原生 `aapt` 编译资源、JDK 8 + `d8` 生成 dex、NDK clang
编译两种 ABI 的 JNI 库、`zipalign` 对齐、`apksigner` 签名。armhf rootfs 的跨架构制作
细节（statx、libatomic、qemu+proot）见 `scripts/build-rootfs-armhf.sh` 顶部注释；
其余版本约束见 [AGENTS.md §6](AGENTS.md#6-构建系统的几个硬性约束)。

## 更新上游版本

```bash
python3 scripts/update-versions.py --dsh 0.2.1 --plugin 3.1.0   # 自动递增 ROOTFS_REVISION
sudo ./scripts/build-rootfs.sh && ./build.sh
```

**dsh / 插件 / Node / Debian 更新时的完整步骤、验证命令，以及本项目对 dsh 做的三处
Android 环境适配（原生缓存、沙箱与审批、硬链接发布），全部写在 [AGENTS.md](AGENTS.md)。**
动手前请先读它——漏掉任一处适配，Agent 会直接无法执行命令。

## 常见问题

**界面是桌面三栏，没适配手机？**
移动插件没加载。检查控制台有无 `required plugins did not activate`，
见 [AGENTS.md §3](AGENTS.md#3-更新移动适配插件)。

**Agent 说沙箱不可用、拒绝执行命令？**
guest 缺少 `DSH_PERMISSION_MODE`。见 [AGENTS.md §2](AGENTS.md#2-更新-dsh)。

**在手机目录里新建文件报 `ENOSYS ... link`？**
该目录所在文件系统（多为共享存储的 sdcardfs/FUSE）不支持硬链接。应用已在启动时
探测并提示；除非打上兼容补丁，否则改用 bash 工具写入，或把工作目录放在 Debian 内部。
见 [AGENTS.md](AGENTS.md) 的硬链接一节。

**能用局域网其他设备访问吗？**
不能，服务只监听 `127.0.0.1`——避免把 Agent 暴露到网络上。

**APK 为什么这么大？**
内置完整 Debian 用户态 + Node.js + dsh。这是"离线开箱即用"的代价。

## 协议

本项目代码以 **GPL-3.0-or-later** 发布，见 [LICENSE](LICENSE)。
随 APK 分发的第三方组件保留各自协议，其中 **PRoot 为 GPL-2.0-or-later**、
**talloc 为 LGPL-3.0-or-later**，详见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

## 致谢

[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) ·
[dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile) ·
[PRoot](https://github.com/proot-me/proot) ·
[Termux](https://github.com/termux/termux-packages) ·
[Debian](https://www.debian.org/) · [Node.js](https://nodejs.org/)

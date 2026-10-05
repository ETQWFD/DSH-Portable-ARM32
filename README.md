# DSH Portable

**在 Android 手机上本地运行完整的 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)** ——
不需要 root，不需要 Termux，装一个 APK 就有。

APK 内置 Debian arm64 根文件系统与 Node.js，通过 [PRoot](https://proot-me.github.io/) 以
**普通应用权限**启动，界面交给 WebView 访问 `127.0.0.1` 上的**标准 `dsh web` 服务**。
不是仿制界面，跑的就是真正的 dsh web。

```
┌─ Android App ───────────────────────────────────────────────┐
│  MainActivity                                               │
│    ├─ 解压内置 rootfs（仅首次，约 1–2 分钟）                 │
│    ├─ ProotLauncher ──► proot -0 -r <files>/debian          │
│    │                       └─ /bin/sh /root/start.sh        │
│    │                            └─ dsh --profile web        │
│    ├─ 从 stdout 解析 token 化 URL                           │
│    └─ WebView.loadUrl("http://127.0.0.1:3081/?token=…")     │
│  KeepAliveService ── 前台服务常驻通知，防后台被杀            │
└─────────────────────────────────────────────────────────────┘
```

## 特性

- **开箱即用**：内置 Debian 13 (trixie) arm64 + Node.js 22 + dsh，首次启动自动解压，无需联网下载环境
- **无需 root**：PRoot 用户态沙箱，普通应用权限即可运行
- **竖屏适配**：内置 [`dsh-web-mobile`](https://github.com/mexiaosqwq/dsh-web-mobile) 插件，
  窄屏抽屉式布局、触屏可用（不改 dsh 源码，走它自己的插件机制）
- **目录挂载**：把手机目录挂进 Debian，一键申请存储权限
- **后台保活**：前台服务 + 常驻通知，可开关
- **端口自适应**：默认端口被占用时自动顺延
- **离线可用**：除模型 API 调用外不依赖网络

## 系统要求

| 项目 | 要求 |
| --- | --- |
| Android | 7.0 (API 24) 及以上 |
| 架构 | **arm64-v8a**（仅此一种） |
| 存储 | 安装后约 **520 MB** 可用空间 |
| 网络 | 仅调用模型 API 时需要 |

## 安装

从 [Releases](../../releases) 下载 `dsh-portable.apk`，或自行构建（见下）。

```bash
adb install -r dsh-portable.apk
```

或直接把 APK 拷进手机点击安装。

> APK 使用自签名证书，安装时系统会提示"未知来源"，属正常现象。

## 使用

1. **首次启动**会解压 Debian 根文件系统，约 1–2 分钟，界面有进度与实时日志。
2. 服务就绪后自动加载 DSH 界面；顶部状态栏显示运行状态，右侧「控制台」可看原始输出。
3. 进入 DSH 后，在 **设置 → Models** 里填入 DeepSeek API Key（保存在设备本地）。
4. **长按顶部左侧的「DSH Portable」标题**进入本应用设置：

   | 设置项 | 说明 |
   | --- | --- |
   | 目录挂载 | 把手机目录挂进 Debian，目标须为 Debian 内空目录 |
   | 服务端口 | 仅监听 `127.0.0.1`；被占用时自动顺延 |
   | 后台保活 | 常驻通知栏防止系统回收服务 |
   | 重新安装环境 | 删掉已解压的 rootfs，下次启动重新解压 |
   | 关于与开源协议 | 组件版本、协议全文、版权声明 |

## 自行构建

### 前置条件

构建脚本面向 **aarch64 Linux**（作者在 Android + Debian chroot 上开发验证）。
需要 root 权限与以下工具：

```bash
apt-get install -y debootstrap openjdk-21-jdk-headless android-sdk-build-tools \
                   aapt zipalign apksigner zip unzip xz-utils python3

# JDK 8：编译用。javac 9+ 的产物会让 R8 内部报错，原因见 AGENTS.md
mkdir -p /opt/jdk8
curl -L https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u452-b09/OpenJDK8U-jdk_aarch64_linux_hotspot_8u452b09.tar.gz \
  | tar -xz -C /opt/jdk8 --strip-components=1
```

Android SDK 只需两部分：

```bash
export ANDROID_HOME=/path/to/sdk
sdkmanager "platforms;android-34" "build-tools;34.0.0"
```

### 构建步骤

```bash
# 1) 生成 rootfs（约 5–15 分钟，需 root）→ app/payload/rootfs/
sudo ./scripts/build-rootfs.sh

# 2) 放入 PRoot 等原生二进制（来源见 THIRD-PARTY-NOTICES.md）
ls build/native/   # libproot.so libproot_loader.so libtalloc.so libandroid-shmem.so

# 3) 打包 APK
./build.sh         # 产物：build/dsh-portable.apk
```

`build.sh` 不依赖 Gradle：直接用 Debian 原生 `aapt` 编译资源、JDK 8 + `d8` 生成 dex、
`zipalign` 对齐、`apksigner` 签名。为什么这么绕见
[AGENTS.md §6](AGENTS.md#6-构建系统的几个硬性约束)。

## 项目结构

```
app/
  AndroidManifest.xml
  res/                                   资源（布局 / 图标 / 文案）
  payload/rootfs/debian-arm64.tar.gz     构建产物，不入库（约 154 MB）
  payload/scripts/start.sh               guest 内启动脚本
  src/io/github/cyf112233/portable/
    MainActivity.java                     启动流程 + WebView
    SettingsActivity.java                 设置（挂载 / 端口 / 保活 / 重装）
    AboutActivity.java                    关于与开源协议
    KeepAliveService.java                 前台服务保活
    DshApp.java                           进程级状态持有者
    core/
      RootfsInstaller.java                首次解压与安装标记
      TarExtractor.java                   TAR 解析（含 GNU 长路径记录）
      ProotLauncher.java                  组装并启动 proot 命令
      Mount.java                          目录挂载配置
scripts/
  build-rootfs.sh                         重建 Debian rootfs
  update-versions.py                      一键抬版本并递增 ROOTFS_REVISION
tools/addzip.py                           按精确路径写入 APK payload
build.sh                                  打包 APK
```

## 更新上游版本

dsh / 插件 / Node 有新版本时如何更新，见 **[AGENTS.md](AGENTS.md)** —— 包含改动位置、
验证命令，以及构建链路的硬性约束与踩坑记录。

```bash
python3 scripts/update-versions.py --dsh 0.2.1 --plugin 3.1.0
sudo ./scripts/build-rootfs.sh && ./build.sh
```

## 常见问题

**界面是桌面三栏布局，没有适配手机？**
移动插件没被加载。检查 rootfs 内 `/root/.dsh/profiles/web/cordis.patch.yml`
是否存在且启用了 `dsh-web-mobile`，以及控制台有无 `required plugins did not activate`。
详见 [AGENTS.md §3](AGENTS.md#3-更新移动适配插件)。

**启动后界面打不开 / 一直转圈？**
点「控制台」看原始输出。常见原因：端口被占用（会写明顺延到哪个端口）、
rootfs 解压不完整（设置里「重新安装环境」后重试）。

**能用局域网其他设备访问吗？**
不能。服务只监听 `127.0.0.1`，这是有意为之——避免把 Agent 暴露到网络上。

**APK 为什么这么大？**
内含完整 Debian 用户态 + Node.js + dsh，压缩后约 154 MB，解压后约 520 MB。
这是"离线开箱即用"的代价。

**支持 x86 手机或模拟器吗？**
不支持，只有 arm64-v8a。

## 协议

本项目自身代码以 **GNU General Public License v3.0 or later** 发布，见 [LICENSE](LICENSE)。

选择 GPLv3 而非 MIT 的原因：APK 内已经分发了 **GPL-2.0-or-later** 的 PRoot 与
**LGPL-3.0-or-later** 的 talloc，二者均可升版至 GPLv3，采用 GPLv3 能让整个分发物的
授权保持一致、合规关系最简单。如果你需要更宽松的授权用于闭源再分发，请注意
PRoot 的 GPL 义务无法通过更换本项目协议来规避。

随 APK 分发的第三方组件保留各自协议。
完整清单、来源与合规说明见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)，
应用内「设置 → 关于与开源协议」也可查看协议全文。

## 致谢

- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) —— Agent 运行时与 Web UI
- [dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile) —— 竖屏 / 触屏适配
- [PRoot](https://github.com/proot-me/proot) —— 无需 root 的沙箱
- [Termux](https://github.com/termux/termux-packages) —— proot / talloc / shmem 的 arm64 构建
- [Debian](https://www.debian.org/) 与 [Node.js](https://nodejs.org/)

# DSH Portable

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
| 架构 | **arm64-v8a** |
| 存储 | 安装后约 520 MB |

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
   | 目录挂载 | 手机目录 ↔ Debian 目录，双向实时；目标目录已有内容会被遮住 |
   | 服务端口 | 仅监听 `127.0.0.1`，被占用时自动顺延 |
   | 后台保活 | 常驻通知栏防系统回收 |
   | 重新安装环境 | 重新解压 rootfs；**`/root` 下的用户数据（API Key、会话、工作区）会保留** |
   | 关于与开源协议 | 组件版本与协议全文 |

## 自行构建

需要 **aarch64 Linux**、root，以及 `debootstrap`、`android-sdk-build-tools`、
`aapt`、`zipalign`、`apksigner`、`zip`、`python3` 和一份 JDK 8（编译用）。

```bash
sudo ./scripts/build-rootfs.sh    # 生成 rootfs（约 5–15 分钟）
./build.sh                        # 产出 build/dsh-portable.apk
```

`scripts/patch-dsh.py` 在生成 rootfs 时自动给 dsh 打两处 Android 适配补丁；
`build.sh` 不用 Gradle：Debian 原生 `aapt` 编译资源、JDK 8 + `d8` 生成 dex、
`zipalign` 对齐、`apksigner` 签名。为什么必须这么绕、以及各工具的版本约束，
见 [AGENTS.md §6](AGENTS.md#6-构建系统的几个硬性约束)。

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

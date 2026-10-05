# 第三方组件与协议

本仓库自身的代码（Android 外壳、启动器、rootfs 构建脚本）以
**GPL-3.0-or-later** 发布，见 [LICENSE](LICENSE)。

构建 APK 时会把下列第三方组件打入产物。**它们的协议不因本项目而改变**，分发 APK
时必须一并遵守。应用内「设置 → 关于与开源协议」可查看这些信息与 GPL-2.0 /
LGPL-3.0 / BSD-3-Clause 的全文。

## 随 APK 分发的组件

| 组件 | 版本 | 协议 | 上游 |
| --- | --- | --- | --- |
| DeepSeek Harness (`@deepseek-ai/dsh`) | 0.2.0-rc.2 | MIT | https://github.com/deepseek-ai/deepseek-harness |
| dsh-web-mobile | 3.0.4 | MIT | https://github.com/mexiaosqwq/dsh-web-mobile |
| PRoot | 5.1.107.96 | **GPL-2.0-or-later** | https://proot-me.github.io/ |
| libandroid-shmem | 0.7 | BSD-3-Clause | https://github.com/termux/libandroid-shmem |
| talloc | 2.5.0 | **LGPL-3.0-or-later** | https://talloc.samba.org/ |
| Node.js | 22.23.3 | MIT | https://nodejs.org/ |
| Debian GNU/Linux | 13 (trixie) arm64 | 各软件包自身协议 | https://www.debian.org/ |

## 关于 GPL / LGPL 组件

`libproot.so`（PRoot）与 `libtalloc.so.2` 是 APK 内**独立分发**的可执行文件与共享库，
不与本项目的 Java 代码静态链接，通过 `Runtime.exec` 以独立进程启动。

本项目整体采用 **GPL-3.0-or-later**，与上述组件的授权兼容：

- PRoot 为 `GPL-2.0-or-later`，其 "or later" 条款允许按 GPLv3 使用；
- talloc 为 `LGPL-3.0-or-later`，可升版至 GPLv3；
- 其余组件为 MIT，MIT 与 GPL 兼容（MIT 部分仍为 MIT）。

如果重新分发本项目的 APK，请注意：

- **PRoot 为 GPL-2.0-or-later**：需随附协议全文并提供对应源码的获取方式。
  PRoot 源码见 https://github.com/proot-me/proot ；本仓库打包的是 Termux
  的构建版本（https://github.com/termux/termux-packages ）。
- **talloc 为 LGPL-3.0-or-later**：以共享库形式分发，需随附协议全文，
  并允许用户以修改后的版本替换该库（APK 中的 `libtalloc.so` 即为该
  共享库，可被替换）。
- **Debian 根文件系统**内含数百个软件包，各自保留其协议（主要为
  GPL-2.0+、GPL-3.0+、MIT、BSD、Apache-2.0 等）。完整清单在系统内
  `/usr/share/doc/*/copyright`。

## 上游二进制来源

| 二进制 | 来源 |
| --- | --- |
| `libproot.so`、`libproot_loader.so` | Termux 包 `proot_5.1.107.96_aarch64.deb`（packages.termux.dev） |
| `libtalloc.so.2` | Termux 包 `libtalloc_2.5.0_aarch64.deb` |
| `libandroid-shmem.so` | Termux 包 `libandroid-shmem_0.7_aarch64.deb` |
| Debian rootfs | `debootstrap --arch=arm64 trixie` |
| Node.js | 官方 `node-v22.23.3-linux-arm64.tar.xz` |

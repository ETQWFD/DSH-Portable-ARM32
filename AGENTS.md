# AGENTS.md — 维护者与自动化代理指引

本文件说明**当上游 DSH / 插件 / Node / Debian 更新时，如何把新版本落进 APK**。
执行顺序、改动位置、以及每一步的验证方式都写在下面，照着做不会漏。

---

## 1. 版本固定在哪些地方

改版本号之前先看这张表。**这些位置必须同时更新**，漏掉任何一处都会产生
「新 rootfs 装不上」或「文档与实际不符」的问题。

| 固定项 | 当前位置 | 现值 |
| --- | --- | --- |
| Debian 版本 | `scripts/build-rootfs.sh` → `DEBIAN_SUITE` | `trixie` (13) |
| Node.js | `scripts/build-rootfs.sh` → `NODE_VERSION` | `22.23.3` |
| dsh | `scripts/build-rootfs.sh` → `DSH_SPEC` | `@deepseek-ai/dsh@0.2.0-rc.2` |
| 移动适配插件 | `scripts/build-rootfs.sh` → `MOBILE_PLUGIN` | `dsh-web-mobile@3.0.4` |
| **rootfs 版本号** | `.../core/RootfsInstaller.java` → `ROOTFS_REVISION` | `9` |
| PRoot / talloc / shmem | `build/native/`（Termux .deb 解出，见 `THIRD-PARTY-NOTICES.md`） | 见文档 |
| Android SDK | `build.sh` → `SDK/build-tools/34.0.0`、`platforms/android-34` | 34.0.0 / API 34 |
| JDK | `build.sh` → `JDK8`、`JAVA21` | `/opt/jdk8`、OpenJDK 21 |

`ROOTFS_REVISION` 是最容易漏的一处：它决定**已安装的设备是否重新解压**。
rootfs 内容一变就必须 +1，否则老用户永远停在旧树。

推荐用脚本一次改完并自动递增：

```bash
python3 scripts/update-versions.py --dsh 0.2.1 --plugin 3.1.0
python3 scripts/update-versions.py --node 22.24.0
python3 scripts/update-versions.py --show          # 查看当前锁定
```

---

## 2. 更新 dsh

dsh 被 **全局安装进 Debian rootfs**（`/opt/node22/lib/node_modules/@deepseek-ai/dsh`），
不是运行时下载，所以更新 = 重建 rootfs。

```bash
# 1) 抬版本（同时自动 ROOTFS_REVISION + 1）
python3 scripts/update-versions.py --dsh <新版本>

# 2) 重建 rootfs（需 root、需 aarch64）——约 5–15 分钟
sudo ./scripts/build-rootfs.sh

# 3) 重新打包
./build.sh
```

**改完必须验证**（缺一不可）：

```bash
# a. rootfs 里真的是新版本
chroot app/../../rootfs/debian /opt/node22/bin/dsh --version   # 若留了工作树

# b. 解包看 APK 里的 dsh 版本（不依赖工作树）
mkdir -p /tmp/v && cd /tmp/v && tar -xzf <rootfs.tar.gz> ./opt/node22/lib/node_modules/@deepseek-ai/dsh/package.json
python3 -c "import json;print(json.load(open('opt/node22/lib/node_modules/@deepseek-ai/dsh/package.json'))['version'])"

# c. 真机冷装：解压无报错 + 界面能起来
pm clear io.github.cyf112233.portable
am start -n io.github.cyf112233.portable/.MainActivity
```

> ⚠️ **dsh 升级最常见的坑**：新版本会带进新的可选重依赖。本项目刻意剔除了
> `@deepseek-ai/libreoffice-kit-wasm`（约 146 MB）和 `sherpa-onnx-linux-arm64`
> （约 39 MB）。如果新版本把它们改成必需，UI 会在启动时报
> `required plugins did not activate`。此时要么把 `build-rootfs.sh` 里的
> `rm -rf` 去掉，要么在 `cordis.patch.yml` 里显式禁用对应插件。

---

## 3. 更新移动适配插件

插件位于 rootfs 的 `/root/.dsh/profiles/web/`，由 `dsh plugin add` 安装，
再由 `cordis.patch.yml` 启用。它负责竖屏布局——**插件缺失或未启用时，
WebView 会渲染桌面三栏布局**，这是最容易误判为"界面坏了"的情况。

```bash
python3 scripts/update-versions.py --plugin <新版本>
sudo ./scripts/build-rootfs.sh
./build.sh
```

**换插件**（不再用 dsh-web-mobile）时，除了改 `MOBILE_PLUGIN`，还要同步改
`build-rootfs.sh` 里写入 `cordis.patch.yml` 的那段（`id` 与 `name` 都要换）。

验证插件确实被加载（服务器起来后）：

```bash
TOKEN=$(<启动日志里的 token>)
curl -sS "http://127.0.0.1:<端口>/?token=$TOKEN" -c /tmp/c -L -o /tmp/boot.html
grep -o 'dsh-web-mobile/client.js' /tmp/boot.html    # 必须出现
```

更彻底的做法是比对插件的 peer 依赖范围是否覆盖本机 dsh 版本：

```bash
npm view dsh-web-mobile peerDependencies --json
```

---

## 4. 更新 Node.js / Debian

```bash
python3 scripts/update-versions.py --node 22.24.0
sudo ./scripts/build-rootfs.sh
./build.sh
```

- **Node**：必须满足 dsh 的 `engines.node`。改大版本（22 → 24）时留意
  npm 全局前缀仍是 `/opt/node22`（`build-rootfs.sh` 里的固定路径），
  若同时改了目录名，需同步改 `RootfsInstaller.java` 中校验
  `opt/node22/bin/node` 的那处 `isInstalled()`。
- **Debian 大版本**（trixie → forky）：改 `DEBIAN_SUITE`，并重新确认
  `debootstrap` 支持该 suite。glibc 版本会影响 Node 官方 tarball 的兼容性。

### 📋 对本项目所做的全部 dsh 兼容改动（更新 dsh 时的完整清单）

dsh 是通用桌面产品，本项目把整棵 dsh 搬进了 PRoot + Android 的环境，因此做了
四处环境适配。**每次更新 dsh 都要逐条复核**，任何一条失效都会让 Agent 直接不可用。

| # | 改什么 | 位置 | 不改的后果 |
| --- | --- | --- | --- |
| 1 | 关闭原生插件缓存 | `app/payload/scripts/start.sh` → `NARB_DISABLE_NATIVE_CACHE=1` | 启动失败：`No usable native binding found for node-addon-require-builtin-linux-arm64-gnu` |
| 2 | 放开沙箱与审批策略 | `start.sh` → `DSH_PERMISSION_MODE=danger-full-access`，外加 profile 的 `sandbox-policy` / `permission` 覆盖 | 每条 shell 命令被拒：`sandbox mode "workspace-write" is requested but no sandbox backend is usable on this host` |
| 3 | 新建文件不再依赖硬链接 | `scripts/patch-dsh.py`（构建时自动套用） | 在共享存储上新建文件失败：`ENOSYS ... link '<暂存路径>'` |
| 4 | 沙箱策略强制全权（忽略会话覆盖） | 同上 `scripts/patch-dsh.py` | Web UI 里选过受限权限的会话永久不可用：`sandbox mode "workspace-write" is requested ...` |

逐条说明：

**1. 原生缓存（`start.sh`）**
`node-addon-native-custom-loader` 会把预编译的 `.node` 物化到 `os.tmpdir()`，之后
只在 sha256 仍匹配时才复用。在 PRoot 里 `os.tmpdir()` 绑定到应用 cache 目录，
**跨重启累积**；一旦留下坏副本，之后每次启动都失败。直接用包内预编译库即可，
所以关掉缓存（`dsh` 在 `dsh-app-boot` 里加载该插件）。

**2. 沙箱与审批（`start.sh` + profile）**
`dsh-base` 里 `mode: !!js process.env.DSH_PERMISSION_MODE ?? 'workspace-write'`，
且 approval 也由同一变量决定（`danger-full-access → 'never'`）。PRoot 内 bwrap 与
Landlock 都不可用，于是受限模式必然拒绝执行；而一次性提权需要审批通道，手机端
（尤其 headless）没有，于是 fail-closed。guest 本身就是隔离层（无特权 Android 应用
+ PRoot + 只绑定用户显式挂载的目录），因此设为 `danger-full-access`。
profile 里的两处覆盖（`sandbox-policy`、`permission.defaultPreset`）是显式声明，
因为 `permission-presets` 要求 sandbox 与 approval 成对匹配，否则报
`composed sandbox and approval defaults match no preset`。

**3. 硬链接（`scripts/patch-dsh.py`）**
见下一节。

**4. 沙箱策略强制全权（`scripts/patch-dsh.py`）**
光设 `DSH_PERMISSION_MODE` 与 profile 覆盖**不够**：那只改部署默认值，而
`sandboxPolicy.resolve()` 的优先级是
`request.mode ?? overrideOf(session) ?? defaultMode`，Web UI 的权限选择器会写入
**会话级覆盖**并优先于默认值。因此补丁直接把 `resolve()` 的返回值固定为
`danger-full-access`，不再读取会话覆盖——否则用户在界面上选过一次受限模式，
那个会话就永久报 `no sandbox backend is usable`。

### 重新解压时的数据保留

`RootfsInstaller.install()` 在清空旧树之前，会把 `<rootfs>/root` 整体移到
`files/preserved-root`，解压完成后**合并回新树**（保留的副本在冲突时优先）。原因：

- `/root` 里是用户数据——`.dsh/.credentials.yaml`（API Key）、`sessions`、
  `workspace`、以及用户可能改过的 profile——这些不属于镜像；
- 而新树里也有用户目录从未有过的东西，比如随包分发的 profile 与移动插件；
- 所以是**合并而非替换**，两边都不可少。

改动这里时务必同时确认：

1. 重新解压后 `.credentials.yaml` 的**权限仍是 0600**（合并时会用
   `Os.stat` 读回原权限，否则 dsh 会拒绝启动）；
2. `sessions/`、`workspace/` 内容在升级后仍存在；
3. 随包分发的 `profiles/web` 与插件在升级后仍然可用。

### ⚠️ dsh 更新后必须复查的平台补丁

`scripts/patch-dsh.py` 会就地修改 dsh 的两个包（`dsh-fs-local` 与
`dsh-sandbox-policy`），
把「新建文件的发布动作」从**只用硬链接**改为**硬链接优先、失败则排他复制**。
原因是 Android 共享存储（`/storage/emulated/0`）在不少 ROM 上是 sdcardfs 或受限
FUSE，对 `link()` 返回 `ENOSYS`，导致 dsh 的 write 工具创建新文件必然失败
（改已有文件走 `rename()`，不受影响）。

补丁由 `scripts/build-rootfs.sh` 自动套用，且**幂等**。但 dsh 更新后它可能失配：

```bash
# 重建时会明确报错，不会静默跳过
python3 scripts/patch-dsh.py rootfs/debian
# 匹配失败时输出：
#   patch-dsh: filesystem publish: expected exactly 1 occurrence of the anchor,
#   found 0; dsh changed shape and this patch needs updating
```

失配时需人工对齐 `patch-dsh.py` 里的锚点常量，并重新确认这三点：

1. 新建文件在 `ENOSYS` 的文件系统上能成功；
2. 目标已存在时仍**拒绝**（`EEXIST`，dsh 据此报 `FS_NOT_OBSERVED`）；
3. 非硬链接类错误（如 `ENOSPC`）必须原样抛出，不能被吞掉。

第 2 点曾经写错过：fallback 里若不加 `COPYFILE_EXCL`，排他语义就丢了。
另外注意 **ESM 下 `node:fs` 的常量在 `constants` 命名空间里**，
`COPYFILE_EXCL` 直接解构是 `undefined`，写出来的标志会等于 `0`。

---

## 5. 更新 PRoot 等原生二进制

`build/native/` 里的四个文件来自 Termux 的 `.deb`，**不随 rootfs 重建而更新**，
需手动替换：

```bash
cd /tmp
curl -O https://packages-cf.termux.dev/apt/termux-main/pool/main/p/proot/proot_<版本>_aarch64.deb
dpkg-deb -x proot_<版本>_aarch64.deb x
cp x/data/data/com.termux/files/usr/bin/proot                  ../build/native/libproot.so
cp x/data/data/com.termux/files/usr/libexec/proot/loader       ../build/native/libproot_loader.so
# 同法取 libtalloc（Termux 包里叫 libtalloc.so.2.5.0）与 libandroid-shmem
```

命名规则**不能改**，原因见下节。替换后务必核对依赖：

```bash
readelf -d build/native/libproot.so | grep NEEDED
# 期望：libtalloc.so.2 / libandroid-shmem.so / libc.so
```

---

## 6. 构建系统的几个硬性约束

改动构建链路前请先读这几条，它们都是踩过的坑：

1. **不能用官方 build-tools 的 `aapt2` / `aarch64-linux-android-*`**
   —— 它们是 x86-64 ELF，在 aarch64 上直接 `Exec format error`。
   本仓库用 Debian 的原生 `aapt` 替代。
2. **javac 必须用 JDK 8**。javac 9+ 生成的 class（含 NestHost 等属性）
   会让 R8 8.2.2 内部抛 `NullPointerException` 而失败。
   而 `d8` 自身需要 Java 11+，所以是「JDK 8 编译 + JDK 21 跑 d8.jar」。
3. **`libtalloc.so.2` 在 APK 内必须叫 `libtalloc.so`**。Android 只解压匹配
   `lib*.so` 的原生库；`.so.2` 会被静默跳过。app 启动时再把副本改名为
   `libtalloc.so.2` 放进私有目录并加入 `LD_LIBRARY_PATH`
   （`ProotLauncher.prepareLinkerLibs`）。
4. **大文件不要走 `aapt -A assets`**。aapt 会把 assets 目录原样吞进 APK，
   再从脚本 zip 一次就会重复嵌入。rootfs 放在 `app/payload/`，由
   `tools/addzip.py` 以 `ZIP_STORED` 写入。
5. **tar 必须保留长路径记录**。Debian 树里存在 200+ 字节的路径，GNU tar
   会用 type `L` 扩展头。`TarExtractor` 已处理；若换成自写打包器，
   必须保证长名记录不被丢弃，否则会出现 `EISDIR`（文件写到目录上）。
6. **rootfs 与 `assets/` 都不要提交**。`.gitignore` 已排除；
   rootfs 由 `scripts/build-rootfs.sh` 现场生成。

---

## 7. 提交前检查清单

- [ ] `python3 scripts/update-versions.py --show` 的三个版本与 README 表格一致
- [ ] 若 rootfs 有变，`ROOTFS_REVISION` 已递增
- [ ] `./build.sh` 通过且 `apksigner verify` 无输出报错
- [ ] 真机 `pm clear` 后冷启动：解压无报错、界面为窄屏布局（非桌面三栏）
- [ ] 控制台无 `required plugins did not activate`
- [ ] `THIRD-PARTY-NOTICES.md` 的版本表已同步
- [ ] 更新过 dsh 后，`scripts/patch-dsh.py` 两处补丁仍能匹配（否则按 §2 对齐）
- [ ] 新增源文件带 `SPDX-License-Identifier: GPL-3.0-or-later` 头
- [ ] 没有把 `.credentials.yaml`、`sk-` 开头的密钥、`*.keystore` 提交进仓库

```bash
# 密钥泄漏自查
grep -rn "sk-" --include='*.java' --include='*.md' --include='*.sh' . | head
```

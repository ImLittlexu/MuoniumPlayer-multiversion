# GOGO / UNM 定制版

这是 [MuoniumPlayer](https://github.com/zihe114514/MuoniumPlayer-multiversion) 的定制 Fork，由 [ImLittlexu](https://github.com/ImLittlexu) 维护。保留原作者署名、许可证与 Git 历史。主要交付目标为 **Minecraft 1.21.1 Fabric / Java 21**；共享代码仍按原项目的七版本规则维护。

## 分支与远程

| 名称 | 用途 |
|---|---|
| `custom` | 默认分支，日常定制、合并上游更新与构建产物 |
| `main` | 原版主线镜像，保持与作者 `main` 一致 |
| `origin` | `https://github.com/ImLittlexu/MuoniumPlayer-multiversion.git` |
| `upstream` | `https://github.com/zihe114514/MuoniumPlayer-multiversion.git`，用于拉取作者更新 |

日常开发在 `custom` 上提交，`git push` 推到自己的 `origin/custom`。当前工作目录已将默认推送远程设为 `origin`，并为 `upstream` 设置禁用推送地址，避免误推作者仓库。这些是本地 Git 配置，不会随 clone 自动复制。

## 已有定制

- GOGO娱乐、UNM小站原生音源：曲库、公开歌单、搜索、筛选、排序与分页。
- 双站公开歌单详情返回恢复原列表；搜索输入使用白色，支持清空关键词并回到当前目录第一页。
- 精确 BV + CID 解析、缓存与恢复；GOGO 为 B站直连 → R2 → 代理，UNM 为 B站直连 → 代理。
- 平台原生逐行歌词、匿名播放统计、Muonium 本地歌单入口与相关布局修复。
- GOGO/UNM 服务器房间一起听：点歌、插队、立即播放、公开/本地混合歌单入队、成员各自取流、预下载与续期。
- 仅两站的非方形封面完整显示，并使用同图模糊补边。
- 两站品牌 Logo 复用 EOE 官网的原始 PNG，内置于模组，音源选择页及侧栏共用，保留颜色、比例和底色。
- IMBlocker 5.6.2.1 焦点状态兼容修复。
- 默认打开界面为 `]`，HUD 编辑为反引号键，快捷搜索默认不绑定。已有 options.txt 的自定义绑定保留。
- 每次进入世界/服务器在本地聊天提示音乐界面和 HUD 编辑的实际绑定；同一连接只提示一次，未绑定时提示到控制设置中绑定。

平台首版免登录，不包含云端收藏、私人歌单或账号功能。定制版按下方规则递增 mod 版本，使用固定 `-eoe` 后缀。安装时替换旧 MuoniumPlayer，不同时装两份。

## Windows 渲染原生库

`2.4.3-eoe` 起，定制主包内置 Windows x64 的 Skija `0.143.17` 原生库、ICU 数据和版本标记。首次启动从 JAR 本地解压并加载，无需联网下载或为安装原生库重启游戏。启动器只需分发主模组，不必额外部署 `config/muonium/native/skija`。其它平台仍沿用伴生包下载机制。

## 双站服务器房间一起听

房间成员均需安装支持 GOGO/UNM 的定制客户端（`2.4.2-eoe` 起）。继续使用原版 mpsync，无需修改服务端或协议。默认开放音源和主机时无需配置；如服主设置了白名单，应追加 `gogo`、`unm` 与下面的代理主机，保留已有条目：

```text
bilibili-audio-proxy-zone-3e7t6w3b97d6-1307216294.eo-edgefunctions.com
```

点歌保留平台业务 ID，以公开代理 URL 携带精确 BV/CID，不向房间发送本机内部播放标识或带签名的 B 站 CDN 地址。每个成员仍按自己的网络情况取流：GOGO 为直连 → R2 → 代理，UNM 为直连 → 代理，线路共用本机缓存。房间曲目实际音频与公布时长相差超过 1 秒时继续回退；本机全部线路失败只在本机报错，不替全房跳歌或寻找同名替身。

整单导入先准备精确时长，最多两条准备线程。房主/管理员按序分批入队；普通成员合帧提交以兼容 mpsync 的导入冷却。服务端的频道限制、导入权限、条目上限和播放时钟照常生效。队列元数据省略 URL 时，客户端从本次房间保存的定位或平台歌曲详情恢复；`next/play` 下发的定位优先。预取不算播放，真实播放继续使用现有匿名统计和原生歌词。

## 产物命名与版本递增

自 2026-11-04 起，新编译交付的可安装主模组统一命名为：

```text
MuoniumPlayer-<游戏版本>-<加载器>-<mod版本>-eoe.jar
```

例如首个按此规则交付的 1.21.1 Fabric 安装包为 `MuoniumPlayer-1.21.1-fabric-2.4.1-eoe.jar`，后续为 `MuoniumPlayer-1.21.1-fabric-2.4.2-eoe.jar`。加载器名称为小写 `fabric`、`forge` 或 `neoforge`，不追加修复内容、功能名或日期。

每次新的编译交付前，mod 版本号最后一位加一。同轮多个游戏版本和加载器使用相同 mod 版本；测试、配置查询以及该轮失败后的排错重试不重复递增。版本同步通过 `bump-version.bat`（`tools/bump-version.ps1`）统一完成，包内版本与文件名中的版本保持一致；可用 `bump-version.bat -WhatIf` 预览下个版本而不写入。

旧产物保留原名作为回退，不追溯改名或覆盖；功能变化记在提交、更新日志和验收记录中。旧 2.4.0 安装包继续保留供回退。

## 首次克隆

```powershell
git clone --branch custom https://github.com/ImLittlexu/MuoniumPlayer-multiversion.git
cd MuoniumPlayer-multiversion
git remote add upstream https://github.com/zihe114514/MuoniumPlayer-multiversion.git
git remote set-url --push upstream disabled://upstream-read-only
git config remote.pushDefault origin
git config push.default simple
git config pull.ff only
git config branch.custom.pushRemote origin
git fetch upstream
git branch --track main upstream/main
git config branch.main.pushRemote origin
```

## 合入作者更新

先提交好当前改动，确认 `git status --short` 为空。`main` 只同步原版；在临时集成分支处理合并并验证后，再更新 `custom`。

```powershell
git fetch upstream
git fetch origin
git switch main
git merge --ff-only upstream/main
git push origin main
git switch custom
git pull --ff-only origin custom
$syncBranch = "integrate/upstream-$(Get-Date -Format yyyyMMdd-HHmmss)"
git switch -c $syncBranch
git merge --no-commit --no-ff upstream/main
```

有冲突时逐文件合并，保留两站身份与持久化、取流顺序、歌词、歌单入口、筛选菜单、封面处理、输入法和默认按键。不能整批选“使用上游”。如本次合并不继续，使用 `git merge --abort` 返回合并前状态。若已显示没有更新，则无须创建合并提交。

按 AGENTS.md 执行七树 Fabric 测试，主要交付树再执行 `:fabric:assemble`。本机 JDK/代理路径按实际环境用命令行覆盖。聚合工具若缺失，逐树执行；既有失败与新增失败分别记录，不删除测试或修改断言掩盖。

确认代码和检查结果后：

```powershell
git add <已解决并检查过的文件>
git commit -m "merge(upstream): 合入上游更新并保留双平台定制"
git switch custom
git merge --ff-only $syncBranch
git push origin custom
```

若上游重写历史，`main` 的快进同步会停止。此时保留旧引用和定制分支，检查差异后处理；不对 `custom` 使用强制推送或硬重置，也不直接在 Fork 的 GitHub 页面点丢弃提交来“同步”定制分支。合并采用正常 merge，保留双方历史。

同步原版 `main` 和合入定制 `custom` 是两步操作，本仓库不自动合并或自动发布未经验证的更新。模组内展示的上游新版也不代表定制包已完成适配；定制安装包应从本 Fork 的构建或发布获取。

## 当前基线

EOE 2.5.2 集成作者 2.5.0 / 2.5.2 主线 `f03826e`，保留此前 `2dda2f6` 与全部定制历史。支持七个游戏版本：1.20.1、1.21.1、1.21.10、1.21.11、26.1.2、26.2、26.3；主要交付目标仍是 1.21.1 Fabric / Java 21。本轮按用户要求通过 `bump-version.bat -Version 2.5.2` 显式对齐，之后默认递增至 2.5.3；重试同一版本不重复递增，非法、倒退或各树版本不一致时拒写。

七树沿用自定义默认按键、连接入场提示与 Windows x64 原生资源内置。设置中心跟随上游：默认外部 MuoniumConfig，启动时自动下载安装更新；旧 builtin 和未知选择迁到 mconfig，已有 OneConfig / ModernUI 选择兼容。外部界面不可用或当前车道没有对应包时回退经典界面，经典界面不再是固定选择。安装成功后仅在主菜单倒计时 15 秒重启，ESC 可取消；世界内不倒计时。旧包被锁定而无法清理时提示手工处理，不自动重启。

接纳上游预设分享、陀螺、可选语音广播、SMTC、LRCLIB 和渲染性能改进。语音广播默认关闭，需玩家与服务器安装 Simple Voice Chat；LRCLIB 只为外部镜像未匹配到曲库的情况兜底。野草自定义源首次装配并启用；已有用户脚本、关闭状态与源选择保留，删除过的源不复活。内置源更新不重新启用已关闭的源。

播放器采用锁外解码和异步释放，保留双站房间取消检查、缓存与线路、原生歌词及统计。B 站分 P 的房间 ID 改用 _pN，兼容旧格式读取，本地缓存与收藏稳定身份不变；双站仍使用原生数字业务 ID。内嵌一起听核心随上游更新（频道自动加入默认关闭），不代表独立服务端已升级。

本轮接纳上游缓存与凭据迁移：缓存位于游戏根 `MuoniumPlayer/cache`，登录凭据位于 `MuoniumPlayer/auth`。普通设置、歌单引用和播放记忆仍在 `config/muonium`，Skija 原生资产仍在 `config/muonium/native/skija`。新位置已有文件优先，迁移失败保留旧文件；目标不可写时登录态可能暂时不可用，解除阻塞后重启可重试。回退旧版前需在关闭游戏后备份两侧目录，并将凭据及缓存恢复到旧版路径，不能只替换 JAR。

Fabric Language Kotlin 沿用原有独立前置要求，不内嵌第二份 Kotlin 运行时。独立一起听服务端未修改，协议版本仍为 v2。版本统一由 `bump-version.bat` 维护七树共 22 处；`-AlignNewTrees` 仅用于历史两树的 2.4.2 版本对齐，正常交付仍执行一次 patch 递增。

自动化结果与游戏内验收分别记录。未实测的登录、切窗播放、HUD 拖动、输入法、声源、运镜与光影效果不宣称已完成实机验证。

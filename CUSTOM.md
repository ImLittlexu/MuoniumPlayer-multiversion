# GOGO / UNM 定制版

这是 [MuoniumPlayer](https://github.com/zihe114514/MuoniumPlayer-multiversion) 的定制 Fork，由 [ImLittlexu](https://github.com/ImLittlexu) 维护。保留原作者署名、许可证与 Git 历史。主要交付目标为 **Minecraft 1.21.1 Fabric / Java 21**；共享代码仍按原项目的五版本规则维护。

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
- 精确 BV + CID 解析、缓存与恢复；GOGO 为 B站直连 → R2 → 代理，UNM 为 B站直连 → 代理。
- 平台原生逐行歌词、匿名播放统计、Muonium 本地歌单入口与相关布局修复。
- 仅两站的非方形封面完整显示，并使用同图模糊补边。
- 两站品牌 Logo 复用 EOE 官网的原始 PNG，内置于模组，音源选择页及侧栏共用，保留颜色、比例和底色。
- IMBlocker 5.6.2.1 焦点状态兼容修复。
- 默认打开界面为 `]`，HUD 编辑为反引号键，快捷搜索默认不绑定。已有 options.txt 的自定义绑定保留。

平台首版免登录，不包含云端收藏、私人歌单或账号功能。定制版按下方规则递增 mod 版本，使用固定 `-eoe` 后缀。安装时替换旧 MuoniumPlayer，不同时装两份。

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

按 AGENTS.md 执行五树 Fabric 测试，主要交付树再执行 `:fabric:assemble`。本机 JDK/代理路径按实际环境用命令行覆盖。聚合工具若缺失，逐树执行；既有失败与新增失败分别记录，不删除测试或修改断言掩盖。

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

截至 2026-11-04，已合入上游 2.4.0 主线 `0ecc122`。定制代码基线 `c7c39c7`，并修复 1.20.1/1.21.1 果冻效果的矩阵配对，以及上游 Windows 图形测试误跳过的问题。

五树完整测试仅保留两项既有失败：横向主题滑条静态约束、checkout 缺少 Lucide 图标生成脚本。1.21.1 Fabric 发布 JAR 已构建；尚未完成游戏内及 Derivative 光影实机验收。世界音箱放置功能的上游适配仍仅在 26.3，不宣称 1.21.1 包含该能力。

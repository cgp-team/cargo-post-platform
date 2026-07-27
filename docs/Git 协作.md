# Git 协作：用分支 + Pull Request 安全提交代码

> **核心原则：永远不要直接提交到 `master` 分支。**
> 每做一件事就开一个新分支，改完后发 Pull Request（简称 PR），由其他人确认后再合并进 `master`。

## 一、一次性准备（只做一次）

### 0. 前置条件：先让管理员把你加进仓库
这是 private（私有）仓库，没权限的话后面每一步都会失败，先做两件事：
1. 注册一个 GitHub 账号。
2. 把你的**用户名**发给仓库管理员，管理员在仓库页面 **Settings → Collaborators → Add people** 里邀请你。你在邮箱或 GitHub 通知里**点接受邀请**。

### 1. 安装 Git
到 [git-scm.com](https://git-scm.com) 下载安装，一路点 Next。装好后右键菜单里会出现 **Git Bash Here**，本文所有命令都在 Git Bash 窗口里敲。

### 2. 配置你的身份（引号里换成自己的信息）
```bash
git config --global user.name "你的名字"
git config --global user.email "你的邮箱@example.com"
```

### 3. 生成 SSH 密钥（相当于你的专属通行证）
```bash
ssh-keygen -t ed25519 -C "你的邮箱@example.com"
```
- 连按 3 次回车，途中问什么都不用填，直接回车。
- 完成后密钥就存进了电脑的 .ssh 目录。

查看并复制你的**公钥**：
```bash
cat ~/.ssh/id_ed25519.pub
```
屏幕会打印出一长串以 ssh-ed25519 开头的字符，**整行**完整复制（包括结尾的邮箱）。

### 4. 把公钥挂到 GitHub 账号上
1. 打开 GitHub 网页，点右上角头像 → **Settings**。
2. 左侧菜单选 **SSH and GPG keys** → 绿色按钮 **New SSH key**。
3. Title 随便写（比如「我的电脑」），Key type 保持默认，Key 一栏粘贴刚才复制的那串字符。
4. 点 **Add SSH key** 保存。

> 🔒 注意：带 .pub 的是公钥，可以贴出去；不带 .pub 的 id_ed25519 是私钥，永远不要发给任何人。

### 5. 测试连接
```bash
ssh -T git@github.com
```
- 第一次会问你 Are you sure you want to continue connecting，输入 `yes` 回车。
- 看到 **Hi 你的用户名! You've successfully authenticated** 就说明通行证生效了 🎉

### 6. 克隆仓库到本地
```bash
git clone git@github.com:用户名/仓库名.git
cd 仓库名
```
> ⚠️ 克隆地址要用 SSH 格式（git@github.com: 开头）。在仓库页面点绿色 **Code** 按钮，切到 **SSH** 标签页复制。别用 https:// 开头的地址，那个每次都要输密码，还经常失败。

## 二、每次改代码的标准流程（⭐ 重点，共 5 步）

### 第 1 步：先拿到最新的 master
```bash
git checkout master
git pull
```
> 养成习惯：动手之前先同步，避免和别人改冲突。

### 第 2 步：新建一个属于自己的分支
```bash
git checkout -b 分支名
```
分支命名建议：`功能/说明`，例如：
- `feature/login-page`（新功能：登录页）
- `fix/header-bug`（修复：头部样式问题）
- `docs/readme-update`（改文档）

> 💡 可以输入 `git branch` 查看自己当前在哪个分支，带 `*` 号的就是。

### 第 3 步：修改代码，然后提交
```bash
git add .                          # 把所有改动加入暂存区
git commit -m "简要说明这次改了什么"   # 提交，附上一句说明
```
提交说明举例：`git commit -m "新增登录页面的表单校验"`

### 第 4 步：把分支推送到 GitHub
```bash
git push -u origin 分支名
```
> 之后再推同一个分支，直接 `git push` 就行。

### 第 5 步：在 GitHub 网页上发 Pull Request
1. 打开仓库的 GitHub 页面，推送成功后页面上方会出现一个黄绿色提示条，点击 **「Compare & pull request」**。
   （如果没有提示条，点上方「Pull requests」→ 绿色按钮「New pull request」，base 选 `master`，compare 选你的分支。）
2. 填写标题和说明：这次改了什么、为什么这么改。
3. 点击 **「Create pull request」**。
4. 等ci/cd进程运行完，没问题再点merge，有问题的话查看ci/cd的反馈并修改。

## 三、PR 发完之后

1. **等待 ci/cd和Review**：其他人点进 PR → 「Files changed」可以看你的改动，有问题在下面直接评论。
2. **要修改怎么办**：在本地继续改代码，然后 `git add .` → `git commit -m "..."` → `git push`。PR 会**自动更新**，无需重新发。
3. **合并**：确认没问题后，点击 PR 页面上的 **「Merge pull request」** → 「Confirm merge」。
4. **收尾**：合并后回到本地，清理现场：
```bash
git checkout master
git pull                # 把合并后的最新 master 拉下来
git branch -d 分支名     # 删掉本地已经用完的分支
```

然后回到「第 1 步」，开始下一个任务。

## 四、命令速查表

| 操作           | 命令                                 |
| ---------------- | ------------------------------------ |
| 看当前在哪个分支 | `git branch`                         |
| 看改了哪些文件   | `git status`                         |
| 切换分支         | `git checkout 分支名`                |
| 新建并切换分支   | `git checkout -b 分支名`             |
| 拉取最新代码     | `git pull`                           |
| 提交改动         | `git add .` → `git commit -m "说明"` |
| 推送分支         | `git push`                           |

## 五、常见问题

**Q1：直接在 master 上改了代码怎么办？**
先把改动带到新分支：

```bash
git checkout -b 新分支名
git add .
git commit -m "说明"
git push -u origin 新分支名
```
然后正常发 PR 即可。master分支设置为不允许直接提交，无需担心。

**Q2：`git push` 提示冲突或被拒绝？**
说明 master 上有别人新的提交。执行：

```bash
git checkout master && git pull      # 先更新 master
git checkout 你的分支
git merge master                     # 把 master 合进你的分支
```
如果提示文件冲突，打开冲突文件，git 会在冲突位置插入三段式标记：

```text
<<<<<<< HEAD
你改的内容
=======
master 上别人的内容
>>>>>>> master
```

手动处理：保留想要的内容，把这几行标记全部删掉，然后：
```bash
git add . && git commit -m "解决冲突" && git push
```
解决不了的话在群里喊和与你代码冲突的人一起看。

**Q3：什么时候该开新分支？**
任何改动都要走「分支 → PR」的流程。

### 总结

>  **同步 master → 开分支 → 改代码提交 → push → 发 PR → 等ci/cd完成并确认无误再合并**
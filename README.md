# -code
毕业论文代码

## 克隆本仓库 / Cloning this Repository

### 错误说明 / About the Authentication Error

如果你在克隆时遇到以下错误：

```
remote: Invalid username or token. Password authentication is not supported for Git operations.
致命错误：'https://github.com/bbb-byte/-code.git/' 鉴权失败
```

这是因为 **GitHub 自 2021 年 8 月起已停止支持通过用户名+密码进行 Git 操作**。你需要改用以下两种方式之一：

> If you encounter this error, it is because **GitHub no longer supports password-based authentication for Git operations** (deprecated August 2021). Use one of the methods below instead.

---

### 方法一：使用 Personal Access Token（推荐）/ Method 1: Personal Access Token (Recommended)

1. 登录 GitHub，前往 **Settings → Developer settings → Personal access tokens → Tokens (classic)**。
2. 点击 **Generate new token**，勾选 `repo` 权限，生成 token 并复制。
3. 克隆时将 token 作为密码使用：

```bash
git clone https://github.com/bbb-byte/-code.git
# Username: 你的 GitHub 用户名 / your GitHub username
# Password: 粘贴刚才生成的 token / paste the token (NOT your account password)
```

或者直接在 URL 中嵌入 token（避免交互式提示）：

```bash
git clone https://<YOUR_TOKEN>@github.com/bbb-byte/-code.git
```

---

### 方法二：使用 SSH / Method 2: SSH

1. 生成 SSH 密钥（如果尚未生成）：

```bash
ssh-keygen -t ed25519 -C "your_email@example.com"
```

2. 将公钥（`~/.ssh/id_ed25519.pub` 的内容）添加到 GitHub：**Settings → SSH and GPG keys → New SSH key**。

3. 使用 SSH 地址克隆：

```bash
git clone git@github.com:bbb-byte/-code.git
```

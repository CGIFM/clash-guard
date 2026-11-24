# Clash Guard · 线路守卫

给 Clash 用的网络守护工具 —— **每 6 秒**检测 ChatGPT / OpenAI / Gemini / Claude / Google 是否真的能用，
不通就自动切到最快且真正可用的节点。

> 本项目和 FlClash / Clash 官方无隶属关系，仅通过 Clash 的标准 RESTful API 工作。

---

## 两个版本

| 平台 | 状态 | 目录 | 说明 |
|---|---|---|---|
| **Android** | ✅ 已发布 | [`android/`](android/) | 独立 App，不依赖电脑 |
| **Desktop** | 🚧 待开发 | [`desktop/`](desktop/) | Windows / macOS / Linux，[规格文档](docs/DESKTOP-SPEC.md) |

### 下载（Android）

从 [Releases](https://github.com/CGIFM/clash-guard/releases) 下载 APK。

> 首次打开需要在设置里填入 Clash 的控制器密钥，详见[使用说明](android/编译说明.md)。

---

## 为什么需要它

挂着代理干活，最烦的就是这几个瞬间：

- 聊着 ChatGPT 突然断，得手动去切节点
- Gemini 提示"你所在地区不支持"，但节点测速明明显示是通的
- 睡前挂着，醒来发现节点早死了
- 手机和电脑都要用，每次翻设置填代理

这个工具把这些活都干了。

---

## 核心能力

### 1. 真实连通性检测，不是 ping

发真实 HTTP 请求并校验返回内容：

| 站点 | 探测地址 | 判定为通 |
|---|---|---|
| ChatGPT | `chatgpt.com/backend-api/me` | 401 / 200 |
| OpenAI | `api.openai.com/v1/models` | 401 / 200 |
| Gemini | `gemini.google.com/generate_204` | 204 **且**地区支持 |
| Claude | `claude.ai/login` | 200 / 302 / 403+`cf-mitigated: challenge` |
| Google | `gstatic.com/generate_204` | 204 |

> **Claude 这条是关键**：它挂在 Cloudflare 人机验证后面，任何脚本都会拿到 `403 Just a moment...`。
> 这其实说明线路通、站点在，所以按"能连上云"判定，不当成断线。

### 2. 出口地区检测（解决 Gemini 假阳性）

**这是最容易被忽略、但最影响体验的一点。**

Gemini 的地区限制看的是**出口 IP 归属地**。只测连通性会得到假阳性——节点显示"通"，
但打开 Gemini 告诉你"你所在地区不支持"。

所以每轮检测都会查一次：

```
https://1.1.1.1/cdn-cgi/trace  →  ip=1.2.3.4  loc=SG
```

不在官方[支持列表](https://support.google.com/gemini/answer/13575153)里的地区（CN / RU / IR / KP 等），
Gemini 直接判为不通，触发切换。

### 3. 全节点扫描，通的挂的都显示

- 递归展开嵌套策略组（`节点选择 → 美国节点 → 具体节点`）
- 自动剔除机场的「信息伪节点」（`剩余流量：499 GB`、`套餐到期：…`）
- 并发测速，**不通的节点照样列出来标红，绝不隐藏**

### 4. 自动择优切换

- 只在用户勾选的节点里选（不勾 = 全部）
- 二次验证：不只看延迟数字，还要真实请求一次确认真能通
- 失败节点冷却 45 秒，不会每轮都撞同一堵墙
- 全部挂了随机轮换续命

### 5. 自启动 / 关联启动 / 自动退出

| 事件 | 行为 |
|---|---|
| 手机开机 | 自动启动 |
| Clash 打开 | 自动关联启动，弹通知 |
| Clash 关闭 | **进程彻底退出**，零耗电 |
| 被系统杀掉 | 定时任务叫醒检查 |

### 6. 局域网共享

自动开启 Clash 的「局域网代理」，App 内显示地址并生成二维码：

```
172.16.30.250:7890
```

别的设备扫码 → 打开带填写说明的网页 → 复制地址即可。

> ⚠️ **扫码不能自动修改对方设备的代理设置** —— iOS / Android 都不允许外部链接改系统代理，
> 这是系统级的安全设计，没有绕过办法。扫码最多省掉"手打地址"这一步。

### 7. DNS 泄露检测

三种状态直观显示：

- `已接管` —— 所有域名解析成 fake-ip（`198.18.x.x`），不泄露
- `有风险` —— 解析出真实 IP 但 DNS 未被接管
- `⚠ 泄露` —— 解析出真实 IP **且**系统 DNS 含国内服务器

---

## 开发

### Android 版

不需要 Android Studio，也不需要 Gradle，纯命令行构建：

```sh
cd android
export ANDROID_SDK=/path/to/android-sdk
export R8_JAR=/path/to/r8.jar
sh build.sh
# 产物：android/out/FlClashGuard.apk
```

详见 [`android/编译说明.md`](android/编译说明.md)。

### 桌面版

**尚未开发。** 完整规格见 [`docs/DESKTOP-SPEC.md`](docs/DESKTOP-SPEC.md)，
里面有技术选型建议、可复用的核心逻辑、以及手机版踩过的所有坑。

---

## 项目结构

```
clash-guard/
├── android/                  Android 版
│   ├── src/com/flclashguard/
│   ├── assets/index.html     界面（原生 HTML，无框架）
│   ├── AndroidManifest.xml
│   └── build.sh              纯命令行构建脚本
├── desktop/                  桌面版（待开发）
└── docs/
    ├── DESKTOP-SPEC.md       桌面版开发规格
    └── screenshot.jpg
```

---

## 已知限制

- **不能免 root 做透明代理**：`iptables` 需要 `CAP_NET_ADMIN`，Shizuku 的 shell 权限也做不到
- **扫码不能自动设代理**：系统限制，见上文
- **关联启动依赖「使用情况访问」权限**：被系统重置后会退化成进程检测，响应变慢
- **主要针对 FlClash 测试**：标准 Clash API 理论上通用，但其他客户端未实测

---

## 许可

[MIT](LICENSE)

本项目**不提供任何节点**，节点请自备。请勿用于商业倒卖。

## 免责

仅供学习与个人使用。使用代理请遵守当地法律法规。

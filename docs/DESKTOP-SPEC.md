# Clash Guard · 桌面版开发规格

> **给接手的开发者（或 AI）的完整任务书。**
> 这份文档描述桌面版需要实现什么、怎么实现、踩过哪些坑。
> 手机版（`android/`）已经跑通并验证过，很多逻辑可以**直接照搬**，本文件会明确指出哪些可以直接抄、哪些需要重写。

---

## 0. 一页速览

| 项 | 内容 |
|---|---|
| 项目名 | **Clash Guard**（不要用 FlClash 命名，那是别人的商标） |
| 桌面版目录 | `desktop/` |
| 目标平台 | Windows 10+ / macOS 12+ / Linux（优先级：Windows > macOS > Linux） |
| 技术栈 | **未定 —— 见 §2 选型建议，你先确认再动手** |
| 核心功能 | 监控 5 个站点连通性 → 不通则测速全节点 → 切最优节点 |
| 和手机版的关系 | 功能对等，逻辑同源，界面各自原生 |
| 必须复用 | 检测判据、地区判定、伪节点过滤、策略组展开算法 |

---

## 1. 它要解决什么问题

用 Clash 的人都有这个痛点：**挂着代理干活，突然某个网站就不通了，得手动去切节点。**

手机版（本项目 `android/`）已经验证这个思路有效。桌面版要做的：

1. **盯着**：每 6 秒检查 ChatGPT / OpenAI / Gemini / Claude / Google 是否能**真正使用**
2. **发现**：一旦有站点不通（或地区不对），立刻动手
3. **出手**：在你允许的节点范围内，测出最快的、且**真实可用**的那个，自动切过去
4. **守夜**：持续运行，失败节点冷却，全挂时轮换续命

---

## 2. 技术选型（需要你先决定）

三个方案，各有取舍：

### 方案 A：Electron + TypeScript ⭐ 推荐

| 优点 | 缺点 |
|---|---|
| 三平台一套代码 | 安装包 ~80MB |
| Web 技术栈，UI 好写 | 内存占用 ~150MB |
| 直接复用手机版的 HTML 面板 | 启动慢（2–3 秒） |
| Clash API 用 `net` 模块直连，无跨域问题 | |

**适合**：最快出成品，UI 体验好，和手机版界面语言一致。

### 方案 B：Tauri + Rust

| 优点 | 缺点 |
|---|---|
| 安装包 ~5MB，省资源 | 需要 Rust 工具链 |
| 启动快，内存 ~50MB | 前端生态没 Electron 成熟 |
| 系统托盘原生支持好 | 打包三平台要各配一次环境 |

**适合**：追求轻量、不想背 80MB 安装包。

### 方案 C：Go + 系统托盘（systray）

| 优点 | 缺点 |
|---|---|
| 单个可执行文件，~10MB | UI 要自己画（WebView 或原生控件） |
| 无运行时依赖 | 开发速度慢 |

**适合**：只想要个安静的后台守护，不需要漂亮界面。

> **建议：选 A（Electron）。** 理由：手机版的面板是纯 HTML，可以直接搬过来改，UI 工作量几乎为零；而且 Electron 的 `net` 模块能绕过浏览器跨域限制直连 `127.0.0.1:9090`。

**决定后请在下面打勾并补一句理由：**

- [ ] A：Electron + TypeScript
- [ ] B：Tauri + Rust
- [ ] C：Go + systray

理由：______________________

---

## 3. 核心逻辑（可直接照搬手机版）

以下逻辑在 `android/src/com/flclashguard/` 里已经跑通并实测验证，**建议逐行对照移植**，不要重新设计。

### 3.1 五站点检测判据 —— 照搬 `Guard.java`

| 站点 | 探测 URL | 判定为通 | 备注 |
|---|---|---|---|
| ChatGPT | `https://chatgpt.com/backend-api/me` | 401 或 200 | 未登录就是 401 |
| OpenAI | `https://api.openai.com/v1/models` | 401 或 200 | |
| Gemini | `https://gemini.google.com/generate_204` | 204 **且**地区支持 | 见 3.2 |
| Claude | `https://claude.ai/login` | 200 / 302 / **403 且带 `cf-mitigated: challenge`** | 见下方说明 |
| Google | `https://www.gstatic.com/generate_204` | 204 | `www.google.com` 可能被干扰，用 gstatic |

**Claude 这条是关键**：它的登录页在 Cloudflare 人机验证后面，任何脚本都会拿到 `403 Just a moment...`。
这其实说明「线路通、站点在」，所以**不能当成断线**。判据是看响应头 `cf-mitigated: challenge`。

**Google 备用**：如果 gstatic 失败，再试 `accounts.google.com/generate_204` 和 `google.com/generate_204`，
**任一通就算通**（anyOf）。

请求头要带：
```
User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36
Accept: */*
Cache-Control: no-cache
```

### 3.2 出口地区检测 —— 照搬 `Guard.checkExitRegion()`

**这是手机版最重要的修正，别漏。**

Gemini 的地区限制看的是**出口 IP 归属地**。只测连通性会得到假阳性——节点显示"通"，但打开 Gemini 说"你所在地区不支持"。

实现：
```
GET https://1.1.1.1/cdn-cgi/trace
→ 解析 ip= 和 loc= 两行
```

备用：`https://www.cloudflare.com/cdn-cgi/trace`

**地区黑名单**（这些地区 Gemini 不可用）：
```js
const GEMINI_BLOCKED = new Set([
  'CN','RU','IR','KP','SY','CU','VE','BY','MM','AF'
]);
```

判定逻辑：
```js
function geminiAllowed(country) {
  if (!country) return true;   // 查不到就别误判
  return !GEMINI_BLOCKED.has(country);
}
```

**必须每轮都查**，不能缓存太久。原因：节点漂移或 Clash 回退直连时，地区会瞬间变回 CN。

> 官方支持列表：https://support.google.com/gemini/answer/13575153

### 3.3 策略组展开 + 伪节点过滤 —— 照搬 `ClashApi.expand()`

真实订阅的结构往往是嵌套的：

```
🚀节点选择 (Selector)
├── ♻️自动选择      (URLTest, 62 个)
├── 🇯🇵日本节点      (URLTest, 15 个)
├── 🇸🇬狮城节点      (URLTest, 15 个)
├── 🇺🇸美国节点      (URLTest, 29 个)
└── [62 个平铺节点]
```

**必须递归展开到底层真实节点**，否则既测不到延迟，切换也会切到"组名"上。

#### 归属判定：按「成员数最少」的子组归

同一个节点常同时属于多个组（`♻️自动选择` 包含所有美国节点）。手机版踩过这个坑：

```
❌ 按「首次出现」归 → 全部归到 ♻️自动选择（62 个），美国节点看不见
✅ 按「成员数最少」归 → 美国节点归到 🇺🇸美国节点（29 个），正确
```

实现：
```js
score = -subGroupMemberCount * 100 + depth;
// score 越大越具体，归属取 score 最大的那个组
```

#### 伪节点过滤

机场订阅里常有假节点：
```
剩余流量：499.42 GB
距离下次重置剩余：28 天
套餐到期：2026-12-20
```

它们不是真节点，测速必然失败。过滤规则：

```js
function isInfoNode(name) {
  if (/[🇦-🇿]{2}/u.test(name)) return false;      // 有国旗 emoji → 真节点
  if (/vless|vmess|trojan|hysteria|hy2|tuic/i.test(name)) return false;
  return /剩余流量|到期|距离下次|重置|套餐|官网|客服|购买|订阅|续费|公告|流量|过期|expire|traffic|remaining|reset/i.test(name);
}
```

#### 策略组类型过滤

这些不是可选节点：
```js
const GROUP_TYPES = new Set([
  'selector','urltest','fallback','loadbalance','relay',
  'direct','reject','rejectdrop','pass','compatible','dns'
]);
```

### 3.4 切换节点：BFS 找路径 —— 照搬 `ClashApi.findPath()`

切到嵌套子组里的节点要**两步**：

```
PUT /proxies/🚀节点选择  {"name":"🇺🇸美国节点"}   ← 先切到子组
PUT /proxies/🇺🇸美国节点 {"name":"🇺🇸美国01"}     ← 再切到具体节点
```

用 BFS 找最短路径：从主组开始，逐层往下找哪个组**直接包含**目标节点。

**踩过的坑**：不要用「当前选中的子组」去推断路径，要找**所有**子组里的那个直接包含目标的。

### 3.5 测速的并发陷阱 —— 一定要看

手机版这里踩了个大坑，导致 **62 个节点全部显示"不通"**：

```java
// ❌ 错误：Future 超时写死
f.get(nodeDelayTimeout + 3000)   // 7 秒

// 62 个节点 × 12 线程池排队，排在后面的还没开始测就被判失败
```

**正确做法：按任务总数算总时限**

```js
const CONCURRENCY = 6;
const totalMs = Math.ceil(pool.length / CONCURRENCY) * (timeoutMs + 2000) + 15000;
const deadline = Date.now() + totalMs;
// 每个 Future 用剩余时间等
```

另外：**FlClash 的 delay 接口高并发会回 503**（`An error occurred in the delay test`）。
实测并发 6 是安全值，并发 12 开始不稳定。

### 3.6 另两个实测数据（供参考）

- **完整扫描 62 个节点耗时约 16 秒**（并发 6）
- **单一节点 delay 请求耗时 100–450ms**

---

## 4. 桌面版独有的部分（手机版没有）

### 4.1 Clash API 地址发现

手机版写死 `127.0.0.1:9090`。桌面版要**聪明一点**：

1. 先试默认 `127.0.0.1:9090`
2. 支持用户手动配置（设置页）
3. **自动探测**：读 Clash 的配置文件
   - Windows: `%USERPROFILE%\.config\clash\config.yaml`
   - macOS: `~/.config/clash/config.yaml` 或 `~/Library/Application Support/clash/`
   - Linux: `~/.config/clash/config.yaml`
   - 从 `external-controller` 字段读地址和 `secret`

```yaml
external-controller: 127.0.0.1:9090
secret: "your-secret-here"
```

### 4.2 系统代理开关（可选，但很有用）

手机版做不到，桌面版能做到：

- **Windows**: 改注册表 `HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings`
  - `ProxyEnable` = 1/0
  - `ProxyServer` = `127.0.0.1:7890`
  - 改完要调 `InternetSetOption` 刷新
- **macOS**: `networksetup -setwebproxy "Wi-Fi" 127.0.0.1 7890`
- **Linux**: `gsettings set org.gnome.system.proxy mode 'manual'`

**建议做成可选项**，默认关闭（改系统设置需要谨慎）。

### 4.3 系统托盘

- 托盘图标颜色反映状态：绿=全通 / 黄=偏慢 / 红=不通 / 灰=待命
- 右键菜单：显示面板 / 暂停监控 / 立即检测 / 退出
- 关闭窗口 = 最小化到托盘（不退出）

### 4.4 开机自启

- Windows: 注册表 `HKCU\...\Run` 或 启动文件夹快捷方式
- macOS: `LaunchAgents` plist
- Linux: `~/.config/autostart/*.desktop`

### 4.5 通知

桌面版用系统通知（不是手机通知）：
- Windows: Toast Notification
- macOS: Notification Center
- Linux: `notify-send`

触发时机和手机版一致：
- Clash 启动 → 监测已开始
- Clash 退出 → 监测已停止
- 自动切换节点 → 已切到 XXX

---

## 5. 界面要求

可以**直接复用手机版的 `android/assets/index.html`**，它是纯 HTML + JS，无框架，改改布局就能用。

需要加的桌面专属部分：
- [ ] 设置页增加「Clash 配置路径」和「自动探测」
- [ ] 增加「开机自启」开关
- [ ] 增加「系统代理」开关
- [ ] 窗口最小化到托盘的提示

界面语言：**中文**。配色沿用手机版的深色主题（`--bg:#0b0f17`，`--accent:#4f8cff`）。

---

## 6. 验收标准

开发完成后，**必须逐条实测**，不能只看代码：

### 基础功能

- [ ] 能读到 Clash 的版本号（`GET /version`）
- [ ] 能列出全部节点，**不通的也显示**
- [ ] 能测出每个节点的延迟，失败的不显示为 0 而显示"不通"
- [ ] 能读出当前出口节点

### 关键判据

- [ ] Claude 检测：`403 + cf-mitigated: challenge` 时**判为通**
- [ ] Google 检测：`www.google.com` 失败时能自动试备用地址
- [ ] 地区检测：切到 CN/RU 地区节点时，Gemini **判为不通**

### 自动切换

- [ ] 手动切坏节点 → 下一轮检测能发现并切回可用的
- [ ] **不通时不会因为"当前节点延迟最低"而卡住不切**（手机版踩过这个坑）
- [ ] 失败节点进入冷却，不会每轮重复撞墙

### 性能

- [ ] 一轮检测（5 站点 + 地区）耗时 < 3 秒
- [ ] 全量扫描（62 节点）耗时 < 25 秒
- [ ] 挂起 1 小时内存增长 < 50MB

### 稳定性

- [ ] Clash 关闭时程序不崩溃，进入待命
- [ ] Clash 重启后能自动恢复监控
- [ ] 网络断开/切换 WiFi 时不崩溃

---

## 7. 已知限制（写进 README，别让用户误会）

1. **扫码不能自动设代理** —— iOS/Android 系统限制，桌面版同理
2. **透明代理需要 root/管理员权限** —— 一般不做
3. **只测试了 FlClash 的 API** —— 标准 Clash API 应该都能用，但需要实测
4. **Gemini 地区黑名单不完整** —— 按官方支持列表来的，新地区变动需要更新

---

## 8. 开发建议顺序

```
第 1 步：能跑通 Clash API（读节点、测延迟、切节点）      ← 先做这个，能验证连通性
第 2 步：五站点检测 + 地区检测                          ← 核心逻辑
第 3 步：自动切换（含失败冷却）
第 4 步：界面（复用 HTML）
第 5 步：托盘、通知、开机自启
第 6 步：系统代理开关（可选）
```

**先做命令行版本验证逻辑**，UI 最后加。手机版就是这么走过来的。

---

## 9. 参考实现

手机版的这些文件是最好的参考：

| 文件 | 看什么 |
|---|---|
| `android/src/com/flclashguard/Guard.java` | 检测逻辑、地区判定、择优算法 |
| `android/src/com/flclashguard/ClashApi.java` | 策略组展开、BFS 寻路、伪节点过滤 |
| `android/assets/index.html` | 界面（可直接改造） |
| `android/src/com/flclashguard/WatchService.java` | 轮询循环、状态机 |

---

## 10. 接手的开发者请在这里签字

- [ ] 我已阅读本文档
- [ ] 我已确认技术选型（§2）
- [ ] 我理解 §3.2 地区检测的必要性
- [ ] 我理解 §3.5 测速并发的坑

签名：____________  日期：____________

---

*文档创建于 2026-11-24。手机版实测数据来自真实 FlClash v1.19.32 + 62 节点订阅。*

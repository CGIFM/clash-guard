# Clash Guard · Desktop

桌面版（Windows / macOS / Linux），**尚未开发**。

## 开始之前

请先完整阅读 **[开发规格文档](../docs/DESKTOP-SPEC.md)**。

那份文档包含：

- 技术选型建议（Electron / Tauri / Go 三选一，附取舍分析）
- 可以直接从 Android 版照搬的核心逻辑（检测判据、地区判定、策略组展开）
- 手机版踩过的所有坑（并发测速、嵌套策略组、Gemini 假阳性）
- 验收标准清单

## 目录约定

```
desktop/
├── README.md          ← 你在这里
├── package.json       (或 Cargo.toml / go.mod，取决于选型)
├── src/               主逻辑
└── ui/                界面（可复用 android/assets/index.html）
```

## 第一步

在 `docs/DESKTOP-SPEC.md` 的 §2 里选定技术栈，然后在本文档记录你的选择：

```
技术选型：
启动日期：
负责人：
```

## 快速开始（选定方案后补充）

```sh
# 待补充
```

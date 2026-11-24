# 构建依赖

`android/build.sh` 需要两个额外的 jar（Shizuku SDK），体积很小但不在 Maven 的默认路径里。

## 下载

```sh
cd android && mkdir -p libs

# Shizuku API
curl -L -o /tmp/api.aar "https://repo1.maven.org/maven2/dev/rikka/shizuku/api/13.1.5/api-13.1.5.aar"
cd /tmp && unzip -o -q api.aar -d api_x && cp api_x/classes.jar <项目>/android/libs/shizuku-api.jar

# Shizuku AIDL（IShizukuService 等桩类，缺了会运行时崩溃）
curl -L -o /tmp/aidl.aar "https://repo1.maven.org/maven2/dev/rikka/shizuku/aidl/13.1.5/aidl-13.1.5.aar"
cd /tmp && unzip -o -q aidl.aar -d aidl_x && cp aidl_x/classes.jar <项目>/android/libs/shizuku-aidl.jar
```

## 作用

用于通过 Shizuku 的高权限（shell uid 2000）执行 `am force-stop`，
从而**静默关闭 Clash 代理而不跳转到 Clash 界面**。

实测：`am force-stop com.follow.clash` 能断掉 VPN 且不切界面；
而 `am stop-service` 会被系统拒绝（`Error stopping service`）。

## 没有 Shizuku 会怎样

App 仍能正常工作，只是开关代理时会短暂切换到 Clash 界面。

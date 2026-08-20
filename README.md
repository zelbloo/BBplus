# BBplus

> 适配 libxposed API 102 的哔哩哔哩增强 Xposed 模组，Kotlin 编写，功能开关于 B 站设置页内开关。

## 适配

- 哔哩哔哩 9.7.0（tv.danmaku.bili）
- libxposed API 102，入口：`io.zel.bbplus.BbplusModule`（`META-INF/xposed/java_init.list`）
- Xposed 环境：LSPosed / NPatch 均可（模块内嵌 `assets/xposed/init.list`，兼容 NPatch 嵌入方式）

## 功能

| 功能 | 说明 |
|------|------|
| 去除「云视听小电视」小弹窗 | 弹幕接口返回的 `activityMeta` 活动数据整体清空 |
| 去除「视频提及」游戏下载内容 | 提及列表/卡片/区域标题中的游戏推广过滤 |
| 去除播放器水印 | 拦截水印数据流与图层视图，替换为空 View |
| 分享按钮改为更多操作 | 详情页点分享（含图标）直接弹出「更多」面板 |

设置入口：`我的 → 设置 → 关于哔哩哔哩 → BBplus 设置`（hook `HelpFragment.onActivityCreated` 注入）。

## 构建

```bash
./gradlew :app:assembleRelease
```

- JDK 17+（`gradle.properties` 中指定了本机 JDK 24）
- 签名：`keystore/` 目录不入库，需自行准备 `keystore/bbplus.keystore`（alias `bbplus`），或临时改用 debug 构建

## 参考来源

- [libxposed](https://github.com/LSPosed/libxposed)：Xposed 接口 API 102
- [NPatch](https://github.com/7723mod/NPatch)：无 root 环境下的模块载体与作用域管理
- [BBZQ](https://github.com/HSSkyBoy/BBZQ)：同类 B 站增强模组，Hook 与设置注入架构参考
- 哔哩哔哩 9.7.0 应用逆向分析（jadx / apktool，`Dy0.h0` 绑定、`KingPositionComponent2$ShareComponent`、`ToolbarRepository` 等混淆符号按 9.7.0 版本实测定位）

## 许可

Mulan PubL v2
# BBplus

> 适配 libxposed API 102 的哔哩哔哩增强 Xposed 模组，全部由 AI 使用 Kotlin 编写。请优先选择[BBZQ](https://github.com/HSSkyBoy/BBZQ)，本模块是在其基础之上添加部分个人向功能。

![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)
![API](https://img.shields.io/badge/libxposed-API%20102-orange)
![License](https://img.shields.io/badge/license-Mulan%20PubL%20v2-blue)

---

## 适配

- 哔哩哔哩 9.7.0（tv.danmaku.bili），其余版本自行尝试
- libxposed API 102，入口：`io.zel.bbplus.BbplusModule`（`META-INF/xposed/java_init.list`）
- Xposed 环境：LSPosed / NPatch 均可

## 功能

| 功能 | 说明 |
|------|------|
| 去除「云视听小电视」贴片 | 弹幕接口返回的 `activityMeta` 活动数据整体清空 |
| 去除「视频提及」游戏下载内容 | 提及列表/卡片/区域标题中的游戏推广过滤 |
| 去除播放器水印 | 移除播放画面上的 bilibili 与 UP 主名半透明水印 |
| 分享按钮改为更多操作 | 详情页点分享（含图标）直接弹出「更多」面板 |
| 净化直播间浮窗 | Hook FastJSON 反序列化，按需隐藏直播间浮窗，支持 8 个子选项：购物卡片 / 购物精选 / 关注提醒 / 直播预约 / 投喂支持 / 滚动横幅 / 电池任务 / 正在去买 |
| 隐藏人气票按钮 | 隐藏直播间底部输入栏旁的「人气」快速送礼圆钮，布局自动补位 |
| 隐藏购物车按钮 | 隐藏直播间底部输入栏旁的「购物袋」入口 |
| 隐藏帮我玩按钮 | 隐藏直播间底部输入栏旁的「帮我玩」互动入口 |
| 隐藏PK赏金周赛贴片 | 隐藏顶栏右侧活动贴片区（PK赏金周赛、礼物星球等活动卡） |

设置入口：`我的 → 设置 → 关于哔哩哔哩 → BBplus 设置`（hook `HelpFragment.onActivityCreated` 注入）。设置面板为卡片式布局，支持深色模式；直播间净化项点击弹出多选列表，开关即时生效。

## 构建

```bash
./gradlew :app:assembleRelease
```

- JDK 17+

## 参考来源

- [libxposed](https://github.com/LSPosed/libxposed)：Xposed 接口 API 102
- [NPatch](https://github.com/7723mod/NPatch)：无 root 环境下的模块载体与作用域管理
- [BBZQ](https://github.com/HSSkyBoy/BBZQ)：同类 B 站增强模组，Hook 与设置注入架构参考
- [BiliRoaming](https://github.com/iAcn/BiliRoaming)：哔哩哔哩增强模组，「净化直播间浮窗」的 FastJSON Hook 思路与目标类来源

## 许可

Mulan PubL v2

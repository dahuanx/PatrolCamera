# 边合区巡查水印相机（PatrolCamera）

一款面向边境经济合作区日常巡查工作的安卓水印相机 App。巡查人员拍照时，App 自动在照片上叠加水印信息，便于留痕、归档与核查。

## 主要功能

- **巡查拍照打水印**：拍摄照片时自动叠加时间、地点、点位等水印信息
- **点位匹配**：内置 51 个边合区巡查点位库，拍照时按 GPS 坐标自动匹配最近点位（WGS-84 坐标系，与手机 GPS 直配）
- **自定义点位**：支持添加自定义巡查点位并本地保存
- **逆地理编码**：根据坐标自动解析行政区划与地名描述
- **经纬度坐标行**：水印中显示系统真实 GPS 坐标（纬度, 经度），可在设置页关闭
- **在线地名（天地图）**：离线点位库未命中时，自动调用天地图逆地理接口把坐标换成真实地名
- **天气信息**：拍摄时可叠加当前天气状况
- **设置页**：支持个性化配置

## 技术信息

- 语言：Kotlin
- 构建：Gradle 8.5（Kotlin DSL）
- 最低支持 Android 版本：见 `app/build.gradle.kts`

## 如何构建

1. 用 Android Studio 打开 `PatrolCamera` 目录
2. 等待 Gradle 同步完成
3. 菜单 Build → Generate Signed App Bundle / APK，或直接 Run 安装到设备

## 如何下载安装（普通用户）

无需构建，直接在手机浏览器打开下面的链接下载安装即可（首次安装需允许"安装未知来源应用"）：

**国内用户推荐（Gitee 直连，速度快）：[Gitee 下载 v1.3.5](https://gitee.com/dahuanxx/PatrolCamera/releases/download/v1.3.5/PatrolCamera-v1.3.5.apk)**

**最新正式版：[v1.3.5](https://github.com/dahuanx/PatrolCamera/releases/latest)**

| 版本 | 下载 | 说明 |
|---|---|---|
| v1.3.5 | [PatrolCamera-v1.3.5.apk](https://github.com/dahuanx/PatrolCamera/releases/download/v1.3.5/PatrolCamera-v1.3.5.apk) | 拍照成功后新增成片缩略图飞入动画 |
| v1.2.9 | [PatrolCamera-v1.2.9.apk](https://github.com/dahuanx/PatrolCamera/releases/download/v1.2.9/PatrolCamera-v1.2.9.apk) | 水印新增经纬度坐标行，设置页可开关 |
| v1.2.8 | [PatrolCamera-v1.2.8.apk](https://github.com/dahuanx/PatrolCamera/releases/download/v1.2.8/PatrolCamera-v1.2.8.apk) | 在线地名自动加省、地市、县区三级行政区前缀 |
| v1.2.7 | [PatrolCamera-v1.2.7.apk](https://github.com/dahuanx/PatrolCamera/releases/download/v1.2.7/PatrolCamera-v1.2.7.apk) | 接入天地图在线地名，点位库未命中时自动解析真实地名 |
| v1.2.6 | [PatrolCamera-v1.2.6.apk](https://github.com/dahuanx/PatrolCamera/releases/download/v1.2.6/PatrolCamera-v1.2.6.apk) | 新增拍照页「刷新位置」按钮，支持强制重新定位 |

全部历史版本见 [Releases](https://github.com/dahuanx/PatrolCamera/releases)。

> 说明：受 GitHub 附件命名限制，安装包在发布页显示为英文名 `PatrolCamera-vX.Y.Z.apk`，内容即对应中文版本号。

## 目录结构

```
边合区巡查相机/
├── PatrolCamera/        # Android 工程源码
│   ├── app/src/main/    # 应用代码与资源
│   └── ...
└── APK/                 # 各版本成品 APK（不进 Git，统一走 Releases 发布）
```

## 版本记录

| 版本 | 说明 |
|---|---|
| v1.3.5 | 拍照成功后屏幕中央浮现成片缩略图，边缩小边飞向右下角并淡出，直观提示已保存（不阻塞连拍） |
| v1.2.9 | 水印新增经纬度坐标行（系统真实 GPS，纬度在前 6 位小数）；设置页新增「经纬度坐标」开关，默认开启；版本说明落款更新为「2025组团式援边绥芬河工作队」 |
| v1.2.8 | 在线地名自动加「省＋地市＋县区」三级行政区前缀（如"黑龙江省牡丹江市绥芬河市 宽沟路 东南约282米"）；库内点位名保持原样不加前缀 |
| v1.2.7 | 接入天地图在线地名（WGS-84 直传不转换）；地名智能清洗与距离分级；设置页新增「在线地名」开关与自定义 Key；同区域 20 分钟内缓存 |
| v1.2.6 | 拍照页新增「刷新位置」按钮，可强制重新定位（丢弃旧缓存位置），2 秒冷却防连点 |
| v1.2.5 | 修复成片水印标题不居中 |
| v1.2.1 及更早 | 点位库、天气、防伪码等基础功能建设 |

## 许可证

本项目采用 [MIT License](LICENSE) 开源。

# Google ↔ OPPO 实况图片转换器（Android）

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF)
![License](https://img.shields.io/badge/License-MIT-blue)
![Release](https://img.shields.io/github/v/release/SB-bey/google-oppo-live-photo-converter)

一个把 **Google 动态图片**、**照片 + 视频** 全部变成 **OPPO 相册可识别的 Live2 实况图片** 的安卓应用，
同时支持 OPPO Live2 反向转回 Google 动态图片。素材既可以来自本地相册，也可以来自 **Google 相册（云端）**。

格式逆向与字节布局参考 [Young-Spark/oppo-live-photo-maker](https://github.com/Young-Spark/oppo-live-photo-maker)（MIT 协议），
已在 ColorOS 14+ 真机样本上验证。

---

## 功能

### 1. 双输入模式（主页顶部切换）

| 模式 | 说明 |
| --- | --- |
| **动态图转换** | Google Motion Photo ↔ OPPO Live2 双向重封装 |
| **照片 + 视频合成** | 选一张封面照片 + 一段视频 → 直接合成 OPPO Live2 实况图（原仓库核心功能） |

### 2. 三种素材导入方式

- **系统照片选择器（Photo Picker）**：Android 13+ 原生，有 Google 服务的设备自动聚合本地相册 + Google Photos 云端内容，**零配置**
- **用 Google 相册导入**：直接打开 Google Photos 应用选图，云端备份的照片也能选，**无需任何配置**；未安装时自动回退系统相册
- **系统文件选择器**：Android 8~12 自动回退，也支持从云盘等文件服务导入

### 3. 设置页（主页右上角齿轮）

- **液态玻璃效果**：半透明磨砂玻璃质感卡片（浅色 / 深色自动适配）
- **主题颜色**：跟随系统动态取色（Material You）/ 蓝色 / 紫色 / 绿色 / 粉色，即时生效
- **转换后自动保存到相册**（Pictures/LivePhotos）
- **HEVC 兼容性提示**开关
- **Google 相册凭据配置**（OAuth Client ID / Secret / API Key）

### 4. 格式能力

- 自动识别 Google Motion Photo / OPPO Live2 / 普通 JPEG，格式标签实时显示
- 内嵌视频 H.264 / HEVC 均可透传（HEVC 可选显示兼容性提醒）
- 转换结果可**分享**或**保存到相册**

---

## 用 Google 相册导入

主页「动态图转换」模式下的 **「用 Google 相册导入」** 按钮会直接拉起 Google Photos 应用
（通过系统 `ACTION_PICK` 指定 Google Photos 包名，Android 11+ 已在 manifest 声明包可见性），
在 Google Photos 里既能选本地照片，也能选 **云端备份** 的照片。**完全不需要任何配置**。
如果设备没装 Google Photos，会自动回退到系统照片选择器。

### （可选）Google Photos Picker API 直连

如需不依赖 Google Photos 应用的云端直连方式，可用官方
[Google Photos Picker API](https://developers.google.com/photos/picker/guides/get-started)（OAuth 2.0 PKCE 公共客户端，凭据只存本机）：

1. 打开 [console.cloud.google.com](https://console.cloud.google.com)，新建项目
2. 「APIs & Services → Library」搜索并启用 **Google Photos Picker API**
3. 「APIs & Services → OAuth consent screen」填应用名，把自己的 Google 账号加为测试用户
4. 「Credentials → Create credentials → OAuth client ID」，类型选 **Web application**，Authorized redirect URIs 填：
   ```
   sbbeylivephoto://oauth2callback
   ```
5. 在 App 的「设置 → Google 相册接入」填入 Client ID（如换 token 报错，再把 Client secret 填上）

> 授权 / 选择 / 下载全程走官方接口；下载的素材缓存到应用私有目录，转换完成后可删除。

---

## 工作原理

OPPO 实况图片 = JPEG + 末尾追加 MP4，加上一组 XMP 元数据 + MPF（Multi-Picture Format）APP2 段。

关键 XMP 字段（缺一不可）：

- `GCamera:MotionPhoto=1` / `MotionPhotoVersion=1`
- `OpCamera:MotionPhotoOwner=oplus`
- `OpCamera:OLivePhotoVersion=2`
- `OpCamera:MotionPhotoFeatureFlag=1`
- `OpCamera:VideoLength=<视频字节数>`
- `Container:Directory` 包含 Primary JPEG + MotionPhoto MP4 两段
- EXIF `UserComment=Oplus_8388608`
- MPF `NumberOfImages=1`，`MPImageType=Baseline MP Primary`

Google 动态图片（Pixel 系尾插式）= JPEG + 末尾追加 MP4 + XMP：

- `GCamera:MotionPhoto=1` / `MicroVideo=1` / `MicroVideoVersion=1`
- `GCamera:MicroVideoOffset=<视频字节数>`

转换流程：

1. 解析 JPEG 段，读取 XMP / MPF，定位内嵌视频起点
   （优先级：`MicroVideoOffset` > `Container:Directory` > `OpCamera:VideoLength` > MPF 条目 > `ftyp` 扫描）
2. 剥离旧元数据段，注入目标格式的 EXIF / XMP / MPF
3. 追加视频字节，得到目标格式的动态图片

核心代码：

| 文件 | 职责 |
| --- | --- |
| `JpegUtil.kt` | JPEG 段扫描 / 剥离 / 插入 |
| `XmpUtil.kt` | XMP 包构造与解析（GCamera / OpCamera / Container） |
| `ExifUtil.kt` | 最小 EXIF（UserComment） |
| `MpfUtil.kt` | MPF APP2 段构造与解析 |
| `MotionPhotoParser.kt` | 格式识别与视频提取 |
| `MotionPhotoConverter.kt` | 双向重封装（Google ↔ OPPO） |
| `GoogleAuth.kt` | OAuth 2.0 PKCE（授权 / 换 token / 刷新） |
| `GooglePhotosClient.kt` | Google Photos Picker API 客户端（会话 / 列表 / 下载） |
| `GooglePhotosActivity.kt` | 云端导入编排（授权 → 选择 → 下载 → 回传） |

---

## 版本记录

| 版本 | 内容 |
| --- | --- |
| v1.0.0 | 首个可用版：Google ↔ OPPO 双向转换，全本地处理 |
| v1.1.0 | Material Design 3 现代化界面：圆角卡片、动态取色、明暗模式 |
| v1.2.0 | 补全逻辑：照片 + 视频 → OPPO Live2 合成模式；新增设置页（液态玻璃 / 主题色 / 自动保存 / HEVC 提示） |
| v1.3.0 | Google 相册（云端）导入：Google Photos Picker API + OAuth PKCE；本地选择升级系统 Photo Picker |
| v1.4.0 | 「用 Google 相册导入」：直接拉起 Google Photos 应用选图（含云端照片），零配置；未安装自动回退系统相册 |

---

## 构建（命令行）

需要 JDK 17 + Android SDK（platform 34 / build-tools 34）。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

# 生成签名密钥（或直接用仓库里的 keystore，别名 motionphoto）
keytool -genkeypair -v -keystore app/keystore/release.keystore -alias motionphoto \
  -keyalg RSA -keysize 2048 -validity 10000 -storepass motionphoto2026 -keypass motionphoto2026 \
  -dname "CN=YourName, OU=LivePhoto, O=YourOrg, L=City, ST=Province, C=CN"

./gradlew assembleRelease
# 输出：app/build/outputs/apk/release/app-release.apk
```

测试：

```bash
./gradlew testDebugUnitTest
# 16 个用例：格式识别 / 字节布局 / 双向往返 / 真实样本端到端 / PKCE / API JSON 解析
```

---

## 兼容性

- minSdk 26（Android 8.0+）
- OPPO 实况识别：ColorOS 14+ 已确认；老固件可能不识别
- HEVC 视频在部分旧机型相册可能无法播放动画（转换后仍可正常当图片打开）
- 「用 Google 相册导入」需要设备安装 Google Photos 应用（未安装会自动回退系统相册）

---

## 许可证

MIT。格式逆向参考了 Google MotionPhoto / GContainer 公开规范与 oppo-live-photo-maker 项目，
未经 OPPO / 欢太 / Oplus 授权，与官方无任何关联。`OpCamera` 等命名空间仅用于格式互操作。

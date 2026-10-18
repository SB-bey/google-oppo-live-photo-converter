# Google ↔ OPPO 实况图片转换器（Android）

把 **Google 标准动态图片（Motion Photo）** 转换成 **OPPO 相册识别的 Live2 实况图片**，
也支持反向转换（OPPO Live2 → Google 动态图片）。两种动态图片标准在一个 App 里互相转换（mixed up）。

格式逆向与字节布局基于 [Young-Spark/oppo-live-photo-maker](https://github.com/Young-Spark/oppo-live-photo-maker)（MIT 协议），
已在 ColorOS 14+ 真机样本上验证过该字节布局。

## 功能

- **一键选择动态图片**（系统文件选择器，图片无需离开手机）
- **自动识别格式**：Google Motion Photo / OPPO Live2 / 普通 JPEG
- **Google → OPPO Live2**：重封装为 OPPO 相册可识别的实况图（`.live.jpg`）
- **OPPO Live2 → Google**：重封装为带 `MicroVideoOffset` 的 Google 动态图片
- 内嵌视频 H.264 / HEVC 均可透传，HEVC 会在界面提示兼容性
- 转换结果可**分享**或**保存到相册**（Pictures/LivePhotos）

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

- `JpegUtil.kt` — JPEG 段扫描 / 剥离 / 插入
- `XmpUtil.kt` — XMP 包构造与解析（GCamera / OpCamera / Container）
- `ExifUtil.kt` — 最小 EXIF（UserComment）
- `MpfUtil.kt` — MPF APP2 段构造与解析
- `MotionPhotoParser.kt` — 格式识别与视频提取
- `MotionPhotoConverter.kt` — 双向重封装

## 构建（命令行）

需要 JDK 17 + Android SDK（platform 34 / build-tools 34）。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

# 生成签名密钥（或直接用仓库里的 keystore，密码见 app/build.gradle 默认值）
keytool -genkeypair -v -keystore app/keystore/release.keystore -alias motionphoto \
  -keyalg RSA -keysize 2048 -validity 10000 -storepass motionphoto2026 -keypass motionphoto2026 \
  -dname "CN=YourName, OU=LivePhoto, O=YourOrg, L=City, ST=Province, C=CN"

./gradlew assembleRelease
# 输出：app/build/outputs/apk/release/app-release.apk
```

测试：

```bash
./gradlew testDebugUnitTest
```

## 兼容性

- minSdk 26（Android 8.0+）
- OPPO 实况识别：ColorOS 14+ 已确认；老固件可能不识别
- HEVC 视频在部分旧机型相册可能无法播放动画（转换后仍可正常当图片打开）

## 许可证

MIT。格式逆向参考了 Google MotionPhoto / GContainer 公开规范与 oppo-live-photo-maker 项目，
未经 OPPO / 欢太 / Oplus 授权，与官方无任何关联。`OpCamera` 等命名空间仅用于格式互操作。

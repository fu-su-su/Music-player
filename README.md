# Music Player

一款 Android 本地音乐播放器，支持歌词同步、封面匹配、歌单管理、AI 助手等功能。

## 功能一览

### 播放
- 本地音频扫描导入（按 title+artist+duration 去重）
- 播放/暂停/上一首/下一首/进度拖动
- 循环模式：顺序/单曲循环/随机
- 后台播放 + MediaSession 通知栏控制
- 睡眠定时（5/15/30/60 分钟）

### 歌词
- 关联本地 LRC 文件
- 逐行高亮同步滚动
- 自动批量匹配（扫描歌词目录，按标题归一化索引匹配，O(1) 复杂度）
- 字号可调（小/中/大/特大）

### 封面
- 手动关联图片
- 扫描图片目录批量匹配（按标题模糊匹配，覆盖模式）
- Glide 加载与缓存

### 歌单
- 创建/重命名/删除歌单
- 添加/移除歌曲
- 歌单内播放

### 主题与外观
- 7 套主题渐变：薄荷绿、樱粉、薄荷、珊瑚、晴空、纯黑、纯白
- 纯黑/纯白主题下文字图标自动反色
- 日间/夜间模式（跟随系统或手动切换）
- 4 种语言：简体中文、繁体中文、English、日本語
- 状态栏/导航栏跟随主题色

### AI 助手
- 内置对话界面，可向 AI 询问音乐相关问题
- 流式响应，聊天记录保存在会话内
- 需要自行配置 API Key（见下文说明）

> **注意**：AI 助手使用智谱 AI（GLM-4-Flash）接口。出于安全考虑，开源版不包含 API Key。使用前请在 `app/src/main/java/com/musicplayer/app/util/AgentService.kt` 第19行填入你自己的 Key（在[智谱 AI 开放平台](https://open.bigmodel.cn/)注册获取）。

### 搜索
- 工具栏搜索框，按歌名/艺术家实时过滤
- 无结果提示，收起自动恢复完整列表

## 技术栈

| 分类 | 技术 |
|------|------|
| 语言 | Kotlin 1.9.20 |
| 构建 | Gradle 8.4 + AGP 8.2.0 |
| 最低 SDK | 23（Android 6.0） |
| 目标 SDK | 34（Android 14） |
| UI | View Binding + RecyclerView + Material Components |
| 数据库 | Room 2.6.1 |
| 图片加载 | Glide 4.16.0 |
| 协程 | kotlinx-coroutines 1.7.3 |
| 架构 | MVVM（ViewModel + LiveData） |

## 项目结构

```
app/src/main/java/com/musicplayer/app/
├── data/           # Room 数据层（Entity / DAO / Repository）
├── playback/       # 播放管理（PlayerManager）
├── service/        # MediaSession 后台服务
├── ui/
│   ├── main/       # 主页（歌曲列表 + 搜索）
│   ├── player/     # 播放页（歌词/封面/进度）
│   ├── playlist/   # 歌单列表与详情
│   ├── settings/   # 设置面板与关于页
│   ├── agent/      # AI 助手
│   └── splash/     # 启动页
└── util/
    ├── AudioUtils.kt    # 音频解析/歌词匹配/封面匹配
    └── ThemeManager.kt  # 主题/夜间模式/语言管理
```

## 构建

### 环境要求

- **JDK 17**（Gradle 8.4 / AGP 8.2 不支持 JDK 22+）
- Android SDK 34（compileSdk）
- 本地有 Android SDK 或通过 Android Studio 安装

### 步骤

1. Clone 仓库

```bash
git clone https://github.com/<你的用户名>/music-player.git
cd music-player
```

2. 配置 SDK 路径（首次构建需要）

在项目根目录创建 `local.properties`，写入你的 Android SDK 路径：

```properties
sdk.dir=C\:\\Users\\<你的用户名>\\AppData\\Local\\Android\\Sdk
```

> 如果用 Android Studio 打开，会自动生成此文件。

3. 构建 Debug APK

```bash
# Windows
gradlew.bat :app:assembleDebug

# macOS / Linux
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

4. 构建 Release APK（需签名配置）

Release 构建需要签名密钥库。在项目根目录创建 `keystore.properties`：

```properties
storeFile=your-release.jks
storePassword=your-store-password
keyAlias=your-alias
keyPassword=your-key-password
```

生成密钥库：

```bash
keytool -genkeypair -v -keystore your-release.jks -alias your-alias \
    -keyalg RSA -keysize 2048 -validity 10000
```

然后构建：

```bash
gradlew.bat :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

> 若不提供 `keystore.properties`，release 构建仍可成功，但产出未签名 APK。

## 截图

<!-- TODO: 添加应用截图 -->

## 贡献

欢迎提交 Issue 和 Pull Request。

1. Fork 本仓库
2. 创建特性分支：`git checkout -b feature/your-feature`
3. 提交更改：`git commit -m 'Add some feature'`
4. 推送分支：`git push origin feature/your-feature`
5. 提交 Pull Request

## License

[MIT License](LICENSE)

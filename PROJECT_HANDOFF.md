# 项目交接文档

## 一、项目基本信息

| 项目 | 内容 |
|---|---|
| 项目路径 | `e:\Music Player` |
| 包名 | `com.musicplayer.app`（debug: `com.musicplayer.app.debug`） |
| 技术栈 | Kotlin + AGP 8.8.1 + Gradle 9.4.1 + Kotlin 2.0.20 + JDK 17 |
| SDK | compileSdk/targetSdk 34，minSdk 23 |
| 数据库 | Room |
| 异步 | Kotlin Coroutines + Flow |
| 图片 | Glide |
| 视图绑定 | ViewBinding |
| AI | 智谱 GLM-4-Flash（API Key已内置） |

## 二、已实现功能清单

### 核心模块
1. **三页面架构**：MainActivity（主页）/ PlayerActivity（播放页）/ SettingsActivity（设置页）
2. **音频播放**：MediaPlayer封装在 [MusicPlayerManager.kt](app/src/main/java/com/musicplayer/app/playback/MusicPlayerManager.kt)，单例模式，支持顺序/单曲/随机循环
3. **数据持久化**：Room（[Song](app/src/main/java/com/musicplayer/app/data/Song.kt) 实体 + [SongDao](app/src/main/java/com/musicplayer/app/data/SongDao.kt) + [MusicRepository](app/src/main/java/com/musicplayer/app/data/MusicRepository.kt)）
4. **导入**：SAF选文件 + 扫描设备MediaStore，去重按 `title+artist+duration`
5. **删除**：列表项右侧删除按钮，确认对话框，级联清理音频/歌词/封面文件
6. **歌词**：LRC格式解析（[LyricParser.kt](app/src/main/java/com/musicplayer/app/util/LyricParser.kt)），RecyclerView滚动，纯文本高亮（无alpha动画）
7. **封面**：批量自动匹配（任意命名，归一化匹配，无名字匹配的图片随机分配，覆盖模式），递归扫描子目录
8. **歌词批量匹配**：同上算法，HashMap索引O(L+S)

### UI 视觉
- 主页/设置/Agent页：绿色渐变背景 `#3a8679 → #bbe59c`（[bg_gradient.xml](app/src/main/res/drawable/bg_gradient.xml)）
- 播放页：纯白根背景（[themes.xml](app/src/main/res/values/themes.xml) 中 `Theme.Player`），全屏图片从顶部100%不透明到底部50%透明渐变（[GradientMaskImageView.kt](app/src/main/java/com/musicplayer/app/ui/player/GradientMaskImageView.kt)，PorterDuff DST_OUT）
- 播放页布局：FrameLayout三层叠加，按10单位坐标分割（0-3图片区/3-4歌曲信息/4-6中部/6-8歌词区/8-9空白/9-10控制区）
- 歌词区：白色文字，高亮18sp粗体，非高亮15sp浅灰 `#B0B0B0`
- 控制区按钮：全部 `ImageButton` + `background="@null"` + `tint="#FF888888"` 灰色，无描边
- Agent气泡：圆角矩形 `bg_chat_bubble.xml`（12dp圆角，白25%透明），输入框胶囊形 `bg_chat_input.xml`

### AI Agent（已完成）
- [AgentService.kt](app/src/main/java/com/musicplayer/app/util/AgentService.kt)：智谱API调用，OpenAI兼容格式
- [AgentTools.kt](app/src/main/java/com/musicplayer/app/util/AgentTools.kt)：12个白名单工具（播放控制/点歌/删除/改歌名/排序/扫描/状态查询/列表搜索等），固定话术拒答无关问题
- [AgentActivity.kt](app/src/main/java/com/musicplayer/app/ui/agent/AgentActivity.kt)：对话页，多轮工具调用循环（最多3轮）
- [AgentBus.kt](app/src/main/java/com/musicplayer/app/util/AgentBus.kt)：StateFlow事件总线（Agent→主页排序切换）
- 入口：主页工具栏AI图标

## 三、待开发需求（用户已确认）

### 需求1：音频焦点与后台播放稳定性
- `AudioManager.requestAudioFocus` + 来电暂停
- 前台服务 `foregroundServiceType="mediaPlayback"`
- MediaSession + 通知栏控制 + 锁屏控制

### 需求3：自定义播放列表（仅创建/重命名/删除）
- 入口：设置页内"我的歌单"项 → 歌单管理页
- 需要：PlaylistEntity + PlaylistDao + 关联表 PlaylistSongCrossRef（多对多）

### 需求4：权限引导页
- 首次启动 → 欢迎页（1屏）→ 隐私协议弹窗 → 权限请求
- SharedPreferences记录 `first_launch` 标志

### 需求5：Android 14 Scoped Storage适配
- SAF + MediaStore混合方案
- `READ_MEDIA_AUDIO` 分区细化处理

### 需求6：国际化（4语言）
- 简中（默认）/ 繁中 / 英语 / 日语
- App名本地化（英：Music Player / 日：ミュージックプレーヤー）
- 文件：`values-zh-rCN` / `values-zh-rTW` / `values-en` / `values-ja`
- 自动翻译后由用户校对

### 需求10：主题与个性化
- 夜间模式：跟随系统 + 手动切换（`values-night` + SharedPreferences）
- 多套主题色：绿（保留）+ 蓝 + 紫 + 粉，4套
- 歌词字号调节：大/中/小三档
- 自定义播放页背景：**给当前这首歌单独设置一张背景图**（不影响其他歌曲）

### 需求11：睡眠定时
- 倒计时后自动暂停（15/30/60分钟/播完当前）

### 需求12：搜索与筛选
- 搜索框位置：主页工具栏，齿轮图标旁边
- 仅搜索，不做文件夹/时长筛选

### 需求（新）：工具栏与设置页重构
- **主页工具栏精简**：只保留 `齿轮(设置入口) + 搜索图标`
- **新建设置页（弹出层）**：点击齿轮后从底部弹出BottomSheet，覆盖3/4屏幕，包含所有原工具栏功能：
  - AI助手
  - 导入音乐
  - 扫描设备音乐
  - 自动匹配歌词
  - 自动匹配图片
  - 排序
  - 主题色切换
  - 夜间模式
  - 睡眠定时
  - 自定义背景
  - 歌词字号
  - 语言切换
  - **关于**（原SettingsActivity内容，含 "Editor by @紤泉" 白25%透明）
- 原 [SettingsActivity.kt](app/src/main/java/com/musicplayer/app/ui/settings/SettingsActivity.kt) 改为"关于页"

## 四、关键架构约定

### 代码风格
- 用户偏好：手动文件操作，不自动改版本，所有相关文件集中存放
- 适配现有版本，不改AGP/Gradle/Kotlin版本
- 文件路径：私有目录 `getExternalFilesDir(null)/{music,covers,lyrics}/`
- 颜色值约定：白25%透明 = `#40FFFFFF`，灰图标 = `#FF888888`

### 数据库演进
- Song 表已有字段：`id, path, title, artist, duration, lyricPath, coverPath, addTime`
- 新增需要：Playlist表 + 关联表，建议加 `version=2` 的 Migration

### 主题切换实现建议
- `ThemeManager` 单例 + `SharedPreferences`
- 主页/设置/Agent/播放页都需要监听主题变化重建（`recreate()`）
- 4套颜色用 `bg_gradient_<color>.xml` 命名

### 工具栏迁移注意
- 当前 [menu_main.xml](app/src/main/res/menu/menu_main.xml) 有6个item，迁移后只保留搜索，其余迁到设置BottomSheet
- [MainActivity.kt](app/src/main/java/com/musicplayer/app/ui/main/MainActivity.kt) 的 `onOptionsItemSelected` 大量逻辑要迁到设置BottomSheet的回调中

## 五、实施路径（建议分4批）

### 第一批（基础架构，独立可测）
- 4语言strings.xml + App名本地化
- values-night/colors-night.xml 夜间模式色板
- ThemeManager + 4套主题色drawable
- BottomSheetSettingsFragment 骨架（点击齿轮弹出）
- 工具栏精简：仅保留齿轮+搜索

### 第二批（页面迁移）
- 原6项工具栏功能迁移到BottomSheet
- 原SettingsActivity改为"关于"页
- BottomSheet各项点击回调连入现有逻辑

### 第三批（核心新功能）
- MediaSession + 通知栏 + 音频焦点（MusicPlayerManager改造）
- Playlist Entity/Dao/Repository + 歌单管理页
- 搜索框（SearchView）+ 模糊搜索逻辑
- 权限引导页（首次启动SplashActivity）

### 第四批（辅助功能）
- 睡眠定时（SleepTimerDialog + CountDownTimer）
- 歌词字号调节（SharedPreferences + LyricAdapter读取）
- 自定义播放页背景（Song表加 `customBackgroundPath` 字段）
- Android 14 Scoped Storage混合方案

## 六、给新对话的开场白模板

> 我有一个Android音乐播放器项目，路径 `e:\Music Player`，包名 `com.musicplayer.app`，技术栈 Kotlin + AGP 8.8.1 + Gradle 9.4.1。已完成三页面架构（主页/播放页/设置页）、Room数据库、MediaPlayer播放、LRC歌词、封面自动匹配、智谱AI助手等功能。
> 
> 接下来要继续开发8个新功能（音频焦点/播放列表/权限引导页/Android 14适配/4语言国际化/主题切换/睡眠定时/搜索），并重构工具栏为"齿轮+搜索"，所有原工具栏功能迁入BottomSheet设置页。请先读取交接文档了解全貌，然后从第一批基础架构开始实施。

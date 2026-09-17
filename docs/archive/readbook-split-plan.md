# ReadBook 拆分方案（god object 解耦）

> 定位：`model/ReadBook.kt` 的职责拆分方案，附接口契约 + 迁移清单。
> 状态：**方案制定，尚未开始改造**。
> 前置分析见 `docs/archive/codebase-coupling-scan-analysis.md`。

## 一、现状定性

`ReadBook.kt`（1375 行 / 50KB）是一个 **无生命周期管理的全局单例**：

```kotlin
object ReadBook : CoroutineScope by MainScope() {
```

它同时干五件事，是整棵依赖树的根：

| 职责 | 代表成员 | 现状问题 |
|------|---------|---------|
| ① 阅读会话状态 | `book` `durChapterIndex` `durChapterPos` `curTextChapter` `bookSource` `callBack` | 全是 public var，20 个文件直接读写 |
| ② 章节加载编排 | `loadContent` `contentLoadFinish*` `chapterLoadingJobs` 三把 `Mutex` `contentProcessor` | 最重的部分，协程编排 + 排版 + 线程安全手工控制 |
| ③ 预下载/离线协调 | `preDownload` `cancelPreDownloadTask` `downloadedChapters` `downloadScope` `preDownloadSemaphore` | 自建 `CoroutineScope(SupervisorJob()+IO)` 常驻 |
| ④ WebDAV 进度同步 | `uploadProgress` `syncProgress` | 依赖 `AppWebDav` |
| ⑤ 阅时统计 | `upReadTime` `flushReadTime` `markReadStart` `readStartTime` `ReadSessionRecorder` | 与①混在一起 |

**关键风险**：引用面 47 处 / 20 文件，且存在 `Book.kt`（数据实体）反向写 `ReadBook.book = null` 这类坏味道。

## 二、拆分目标

拆成 5 个单一职责组件，每个组件通过 **接口契约** 提供能力，`ReadBook` 退化为薄门面（见迁移策略）。

组件归属模块放在 `model/` 下，新增 `model/read/` 子包收敛 ②③。

---

## 三、目标组件划分

### A. `ReadingSession`（Kotlin class，Hilt `@Singleton`）

对 UI 的核心会话状态 + 导航 + 进度落库。**体量最大，保留在 `model/`**。

**状态**（原为 public var，拆后收敛为组件内部 + 对外只读/受限写）：
- `book: Book?`（对外只读，写走 `open(book)`）
- `bookSource: BookSource?`
- `durChapterIndex / durChapterPos`（对外只读）
- `curTextChapter / prevTextChapter / nextTextChapter`
- `chapterSize / simulatedChapterSize`
- `callBack: CallBack?`（`register/unregister` 托管）

**接口契约**：

```kotlin
// model/read/Contracts.kt
interface SessionOpener {
    /** 换书 or 打开正文页 */
    fun open(book: Book)
    fun reopen(book: Book)
}

interface SessionNavigator {
    val durChapterIndex: Int
    val durChapterPos: Int
    val simulatedChapterSize: Int

    fun moveToNextPage(): Boolean
    fun moveToPrevPage(): Boolean
    fun moveToNextChapter(upContent: Boolean, upContentInPlace: Boolean = true): Boolean
    suspend fun moveToNextChapterAwait(upContent: Boolean): Boolean
    fun setProgress(progress: BookProgress)
    fun saveCurrentBookProgress()
    fun restoreLastBookProgress()
    fun jumpToChapter(index: Int)
}

interface SessionUiBridge {
    val callBack: ReadBook.CallBack?
    fun register(cb: ReadBook.CallBack)
    fun unregister(cb: ReadBook.CallBack)
    fun upMsg(msg: String?)
    fun setCharset(charset: String)
    fun pageAnim(): Int
}
```

**迁移进来**：`resetData` `upData` `upWebBook` `upReadBookConfig` `saveRead` `pageAnim` `setCharset` `setProgress` `saveCurrentBookProgress` `restoreLastBookProgress` `register` `unregister` `upMsg` `moveTo*`。`clearTextChapter`/`clearTextChapterIfThemeChanged` 归入排版控制（见 B）。

### B. `ChapterLoadCoordinator`（Kotlin class，Hilt `@Inject`）

章节内容获取 → 预处理 → 排版编排，是线程安全最重灾区（`chapterLoadingJobs` + 三把 `Mutex` + 一堆 `@Synchronized`）。

**放到 `model/read/` 子包**，隔离它的大量协程/排版 import。

```kotlin
// model/read/ChapterLoadCoordinator.kt
class ChapterLoadCoordinator @Inject constructor(
    private val session: ReadingSession,   // 依赖 A，不反向依赖
    private val contentProcessor: ContentProcessor
) {
    /** 加载指定索引章节正文，可触发排版 */
    fun loadContent(index: Int = durChapterIndex, upContent: Boolean = true, ...)
    fun loadContent(resetPageOffset: Boolean = false, forceReload: Boolean = false)
    suspend fun loadContentAwait(index: Int, ...): Boolean

    /** 排版完成回调（内部编排 A 的三个 TextChapter + layoutChannel） */
    fun contentLoadFinish(book: Book, chapter: BookChapter, content: String, ...)
    suspend fun contentLoadFinishAwait(...)
    suspend fun contentLoadFinishLazy(...)

    /** 目录更新后刷新排版 */
    fun onChapterListUpdated(newBook: Book, loadContent: Boolean, isIncremental: Boolean)

    fun clearTextChapter()
    fun clearTextChapterIfThemeChanged(): Boolean
}
```

装载逻辑（`download`/`downloadAwait`/懒加载回调）仍复用 `WebBook` / `CacheBook`，文案和错误处理收敛于此。

### C. `DownloadCoordinator`（Kotlin class，Hilt `@Inject`）

预下载 + 离线缓存状态。自建的`downloadScope`、`Semaphore` 全部内聚到本组件。

**放到 `model/read/` 子包**。

```kotlin
// model/read/DownloadCoordinator.kt
class DownloadCoordinator @Inject constructor(
    private val appDb: AppDatabase,
    private val session: ReadingSession,        // 依赖 A
    private val loader: ChapterLoadCoordinator  // 依赖 B，触发正文预热
) {
    val downloadedChapters: Set<Int>       // 对外可读
    val downloadFailChapters: Map<Int, Int>
    fun downloadChapter(index: Int)
    fun preDownload()
    fun cancelPreDownloadTask()
    fun upToc()                             // 目录更新（原属 A，但只在预下载/装载场景触发）
    @Volatile var isLoading = false
}
```

**迁移进来**：`downloadIndex` `download` `downloadAwait`（下载部分）`preDownload` `cancelPreDownloadTask` `upToc` `downloadedChapters` `downloadFailChapters` `downloadScope` `preDownloadSemaphore` `preDownloadTask`。`addLoading/removeLoading/loadingChapters` 为 C 与 B 共享，建议抽到 `ChapterLoadCoordinator`（负责"正在加载中"标志位）。

> 依赖方向：C → B → A，**单向**，杜绝反向。

### D. `ProgressSync`（Kotlin class，Hilt `@Inject`）

WebDAV 上传/同步，独立于阅读主流程。

```kotlin
// model/ProgressSync.kt
class ProgressSync @Inject constructor(
    private val appWebDav: AppWebDav,
    private val session: ReadingSession
) {
    fun uploadProgress(toast: Boolean = false, successAction: (() -> Unit)? = null)
    fun sync(
        newProgressAction: ((BookProgress) -> Unit)? = null,
        uploadSuccessAction: (() -> Unit)? = null,
        syncSuccessAction: (() -> Unit)? = null
    )
}
```

### E. `ReadRecordTracker`（Kotlin class，Hilt `@Inject`）

阅时统计，落库走已有的 `ReadSessionRecorder`。

```kotlin
// model/ReadRecordTracker.kt
class ReadRecordTracker @Inject constructor(
    private val session: ReadingSession
) {
    fun onPageTurn()          // 原 upReadTime（翻页心跳）
    fun onPauseOrExit()       // 原 flushReadTime
    fun onOpenBook()          // 原 markReadStart
}
```

### DI 挂载

项目已有 `SingletonComponent` + `AppModule`，新增：

```kotlin
// di/ReadModule.kt
@Module
@InstallIn(SingletonComponent::class)
object ReadModule {
    @Provides @Singleton
    fun provideReadingSession(): ReadingSession = ReadingSession()
}
```

`ChapterLoadCoordinator` / `DownloadCoordinator` / `ProgressSync` / `ReadRecordTracker` 用 `@Inject` 构造注入，由 Hilt 组装依赖链。**注意**：本工程重度使用 `object` 单例（`AudioPlay` `ReadManga` `CacheBook` 同理），这里只对 `ReadBook` 试点注入，避免一次性推翻全工程惯例。

---

## 四、迁移策略（关键，避免一次炸 47 处）

god object **不能硬拆**，否则 20 个调用点全部编译失败。分四步：

### 步骤 1：组件建设 + 门面转发（不改外部调用）
新建 A~E 组件，内部实现从 `ReadBook` 平移。`ReadBook` 保留原方法签名，转为**转发门面**：

```kotlin
@Deprecated("改用 ReadingSession/DownloadCoordinator 等组件")
object ReadBook : CoroutineScope by MainScope() {
    // 状态转发
    var book: Book?
        get() = session.book
        set(v) { session.book = v }
    var durChapterIndex: Int
        get() = session.durChapterIndex
        set(v) { session.durChapterIndex = v }

    // 方法转发
    fun loadContent(i: Int, ...) = loader.loadContent(i, ...)
    fun saveRead() = session.saveRead()
    fun upToc() = downloader.upToc()
    // ...其余方法全部转发
}
```
> 此阶段外部 47 处引用零改动，`ReadBook` 只是瘦了实现。

### 步骤 2：按「调用面最小、收益最大」优先迁移调用点
迁移顺序（每迁完一批删对应转发方法，用 Deprecated + 编译告警盯漏网）：

1. **Service/Receiver**（`BaseReadAloudService` `ExportBookService` `MediaButtonReceiver`）→ 只读 `session.book/curTextChapter`，改成注入 `ReadingSession`。
2. **ViewModel 层**（`ReadBookViewModel` `BookInfoViewModel` `TocViewModel` `MainViewModel`）→ `onChapterListUpdated` 改走 `loader`。
3. **UI 层**（`ReadBookActivity` `ReadMenu` `ContentEditDialog` 等 Dialog）→ 通过 `@HiltViewModel` / `@AndroidEntryPoint` 注入组件。
4. **entity/Book.kt 的反向依赖**（`ReadBook.book = null`）→ 这是最脏的一处，entity 不该知道全局状态。改成 `ReadBookEvents.onBookDeleted()`（事件 + `@ActivityRetained` 持有）或让调用方在删书时显式清 session。

### 步骤 3：删除门面转发
调用点全部迁完后，删除 `ReadBook` 的转发方法与废弃状态，仅保留 `CallBack` 接口定义（或迁到 `ui/book/read/ReadBookCallback.kt`）。

### 步骤 4：统一生命周期
原 `: CoroutineScope by MainScope()` 常驻作用域取消。各组件负责作用域：
- 阅读主流程 → `ReadingSession` 用 `@Singleton` + `SupervisorJob`，明确在 `open(book)`/`onDestroy` 取消。
- 预下载 → `DownloadCoordinator.downloadScope` 内聚，`cancelPreDownloadTask` 时统一 `cancelChildren()`。
- 不再出现全局裸 `MainScope()`。

---

## 五、迁移清单（按调用点）

| 调用文件 | 当前引用 | 迁到组件 | 优先级 |
|---------|---------|---------|-------|
| `data/entities/Book.kt` | `ReadBook.book = null` | 事件化，删 entity→全局依赖 | P0 |
| `BaseReadAloudService.kt` | `book/curTextChapter/prevTextChapter` | `ReadingSession` | P0 |
| `ReadBookActivity.kt` | `register/unregister/loadContent/saveRead/cancelPreDownloadTask` | A + B + C | P0 |
| `ReadBookViewModel.kt` | `resetData/loadContent/onChapterListUpdated/bookSource/upMsg` | A + B | P0 |
| `MediaButtonReceiver.kt` | `resetData/loadContent/readAloud` | A + B | P1 |
| `ExportBookService.kt` | `onChapterListUpdated` | B | P1 |
| `BookInfoViewModel.kt` | `book/onChapterListUpdated/saveRead` | A + B | P1 |
| `TocViewModel.kt` | `onChapterListUpdated` | B | P1 |
| `MainViewModel.kt` | `onChapterListUpdated` | B | P1 |
| `CacheBook.kt` | `downloadedChapters/downloadFailChapters/contentLoadFinish` | C + B | P1 |
| `ContentEditDialog.kt` | `book/durChapterIndex/loadContent` | A + B | P2 |
| `BookController.kt`(api) | `book` | A | P2 |
| `SourceHelp.kt` | `bookSource` | A | P2 |
| `ReadAloud.kt` / `ReadAloudActivity.kt` / `ReadAloudDialog.kt` | `book/curTextChapter/durChapterIndex` | A | P2 |
| `BaseReadBookActivity.kt` | `book/msg/loadContent` | A + B | P2 |
| 各 Dialog（`ReadMenu` `MoreConfigDialog` `ReadStyleDialog` `AutoReadDialog` 等） | `book/loadContent/callBack` | A + B | P3 |

优先级依据：**实体反向依赖（P0）先破**，否则 entity 永远耦合全局；Service/Receiver 常驻生命周期最吃亏；纯 UI 的 Dialog 最后收尾，风险小。

---

## 六、验收标准

1. `ReadBook.kt` 仅剩 `CallBack` 接口（或迁走），无任何 `var` 状态与协程作用域。
2. `grep -rn "ReadBook\.book" app/src/main` 计数为 0（entity/service 不再碰全局状态）。
3. 依赖方向单向：`DownloadCoordinator → ChapterLoadCoordinator → ReadingSession`，用 `ArchUnit` 或 KtLint 依赖规则卡死。
4. `ChapterLoadCoordinator` 线程安全不再靠手写 `@Synchronized`，收敛到 `chapterLoadingJobs` + 三把 `Mutex` 内部封装的单一入口。
5. 构建通过 + `ReadBook` 相关场景（换书/翻页/预下载/朗读断点/WebDAV 同步/阅时统计）冒烟回归。

## 七、风险与注意

- **`Book.kt` 反向依赖是最大雷**：entity 层引入 `ReadBook` 会让整个 `data/entities` 依赖 UI 状态。方案用事件解耦，且配合测试覆盖删书流程。
- **`CallBack` 是 View 层接口**：原定义在单例里，迁移时把它挪到 `ui/book/read/` 包，语义才正确。
- **不要一上来就并进 Hilt**：工程其他单例（`AudioPlay` 等）仍是裸 `object`，`ReadBook` 单独注入没问题，但别顺手把别人也拽进来，控制改动面。
- **懒加载（`LazyContentManager`）与排版强耦合**：`contentLoadFinishLazy` 归 B，任何 B 的重构都要回归懒加载书源场景（`nextPageLazyLoad`）。
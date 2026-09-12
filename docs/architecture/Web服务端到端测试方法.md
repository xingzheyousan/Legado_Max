# Web 服务端到端（E2E）测试方法

> 适用范围：验证应用内置 Web 服务（传书、快速备份、书架/书源接口等）的真实可用性——覆盖「浏览器网页操作 → HTTP 接口 → App 内效果」全链路。端到端即 E2E（End-to-End）测试：以真实用户视角走完整条系统链路，而非只测单个函数或模块。
> 有效状态：长期有效的技术笔记，工具链不变即持续有效；与具体 bug 无关，任何涉及 Web 服务的改动都可用它做回归验证。

## 一、总览：三件套组合

| 工具                                          | 角色                                                                       |
| --------------------------------------------- | -------------------------------------------------------------------------- |
| `android-emulator` MCP（或 gradlew + 裸 adb） | 构建安装被测包、启动应用、UI 自动化开启 Web 服务开关、验证书架 UI 与 Toast |
| `adb forward` 端口转发                        | 把电脑的 `127.0.0.1:1122` 隧道到设备里应用的 Web 服务，电脑浏览器即可访问  |
| `browser-use` MCP（`node_repl` + 内置浏览器） | 以真实浏览器走网页全流程：读页面、模拟点击、监听下载、校验渲染结果         |

三层验证缺一不可：**接口层**（curl）证明服务端行为正确；**网页层**（内置浏览器）证明前端链路与交互正确；**App 端**（UI 树/日志）证明用户可见效果正确。传书 bug 的教训是三层各自正确性都不能靠推测——最终根因（content-type 重写丢 boundary）只有接口层实测才能暴露。

## 二、构建与安装被测包

```bash
# 编译打包（多变体项目必须用完整任务名，compileDebugKotlin 会歧义）
gradlew.bat -p <项目根> :app:assembleAppMaxDebug

# 安装（adb 不在 PATH 时用全路径；install -r 保留设备上的既有数据）
adb -s <serial> install -r app/build/outputs/apk/appMax/debug/*.apk
```

- 设备 serial 用 `adb devices` 查（已在运行的模拟器/真机直接用，不必新起 AVD）。
- 注意 APK 文件名带版本时间戳，重装前先 `ls` 取实际文件名。

## 三、设备上开启 Web 服务

应用内「我的」页的 Web 服务开关不会跨进程重启自动拉起，重装后要手动开一次。UI 自动化路径：

1. `android_ui_describe`（或 `adb exec-out uiautomator dump /dev/tty`）读界面树定位元素；
2. 点底部「我的」→ 上滑滚动 → 找到「Web 服务」行（`text="Web 服务"`）→ `android_ui_tap` 点击；
3. 确认启动：`adb shell dumpsys activity services <包名> | grep WebService`，logcat 会广播 `http://<设备IP>:<端口>`（默认 1122）。

## 四、adb 端口转发

```bash
adb -s <serial> forward tcp:1122 tcp:1122
curl -s -m 8 -o /dev/null -w "%{http_code}" http://127.0.0.1:1122/getBookshelf   # 连通性检查
```

- 之后电脑上**任何浏览器/工具**访问 `http://127.0.0.1:1122/` 都等于访问设备里的 Web 服务，不要求电脑与设备同网段。
- 局域网内其他设备也可以直接访问设备显示的 `http://<设备IP>:1122`，这是功能本身的正常用法；转发只是测试上更稳。

## 五、接口层验证（curl）

```bash
# 书架（GET，ReturnData JSON）
curl -s http://127.0.0.1:1122/getBookshelf

# 传书（POST multipart：fileName 为普通字段，fileData 为文件）
curl -s --form-string "fileName=测试.txt" \
  -F "fileData=@C:/path/to/测试.txt" http://127.0.1:1122/addLocalBook

# 备份三个端点：流式 zip、尾斜杠路由、预览明细
curl -s -D headers.txt -o backup.zip http://127.0.0.1:1122/backup
curl -s -o backup2.zip http://127.0.0.1:1122/backup/
curl -s http://127.0.0.1:1122/backupPreview
```

校验要点：`Content-Type: application/zip`、`Content-Disposition: attachment`、zip 可解压且含 bookshelf.json 等条目（`python -m zipfile -l backup.zip`）。

**坑**：Git Bash 下 `-F "@$TEMP/文件"` 会被 MSYS 路径转换弄坏 multipart boundary，报 "boundary missing"。用 Windows 风格路径（`C:/...`）+ `--form-string` 传文本字段。

## 六、网页 UI 验证（browser-use / 内置浏览器）

加载 `browser-use:control-browser` 技能后，通过 `mcp__node_repl__js` 执行。每次调用都是全新内核，必须先跑 bootstrap 再重建 browser 绑定。核心套路：

```js
// bootstrap（每次调用开头都要）
const { setupBrowserRuntime } = await import(
  pathToFileURL(
    join(process.env.ZCODE_PLUGIN_ROOT, "scripts", "browser-client.mjs"),
  ).href
);
await setupBrowserRuntime({ globals: globalThis });
const browser = await agent.browsers.getForUrl("http://127.0.0.1:1122/");

// 打开页面 → 读无障碍树（定位元素的唯一依据）
const tab = await browser.tabs.new();
await tab.goto("http://127.0.0.1:1122/vue/index.html#/backup");
await tab.playwright.waitForLoadState({ state: "domcontentloaded" });
await tab.playwright.domSnapshot();

// 模拟点击（先 count() 确认唯一再点）+ 监听下载事件
const downloadPromise = tab.playwright.waitForEvent("download", {
  timeoutMs: 60000,
});
await tab.playwright
  .getByRole("button", { name: "点击下载备份压缩包" })
  .click();
const download = await downloadPromise; // 触发即证明浏览器下载链路通

// 校验渲染结果（如「备份成功」面板）
await tab.playwright.getByText("备份成功").isVisible();
```

**坑**：

- 内置浏览器点击**相对路径链接**可能不跳转（导航页「快速备份」链接实测两次无效）；从快照取 `/url` 直接 `goto` 同一地址即可，普通浏览器无此问题。
- hash 路由（`#/backup`）的 `waitForURL` 容易超时，点击后用 `tab.url()` 轮询判断。
- download 对象不能 `String()` 序列化，直接判定事件已触发即可。
- `Stringifying` 返回值报 "Cannot convert object to primitive value" 时，检查是不是把不可序列化对象放进了返回表达式。

## 七、App 端效果验证

- **书架出现新书**：切到书架 tab 后用 `android_ui_describe` / uiautomator dump 在界面树里找书名文本（不要依赖截图）。
- **Toast 是否弹出**：截图抓不到（2 秒即逝），看 logcat——`NotificationService: Toast already killed. pkg=<包名>` 或 SurfaceFlinger 销毁 `Toast#0` 图层即为弹过。
- **文件落盘**：`adb shell ls -la /sdcard/Download/`（保存位置设为 Download 时）。
- **失败路径也要测**：例如未设置「书籍保存位置」时先传一次，确认返回 `isSuccess:false` 与明确文案，再设置后重试成功路径。

设置「书籍保存位置」的 UI 自动化路径：我的 → 其它设置 → 滚动到「书籍保存位置」→ 系统文件夹选择器 → 选目录 → 确认「选择」。设置结果可 dump 界面树确认 `content://...` 摘要。

## 八、已知坑清单（工具链）

- **设备截图通路白屏**：部分模拟器/远程屏上 MCP screenshot 与 `adb screencap` 都返回同一张白图；一律改用 UI 树（`uiautomator dump`）验证界面。
- **启动 Activity 被劫持**：`am start` 按 LAUNCHER category 会命中 debug 包的 LeakCanary 入口页，需显式 `-n <包名>/io.legado.app.ui.main.MainActivity`。
- **android-emulator MCP 钉死会话工作区根**：`projectDir`/`apkPath` 传 worktree 或仓库外路径报 "Path escapes project root"，此时改用 gradlew + 裸 adb。
- **npm install 的 `--prefix` 污染**：在主仓库 CWD 下对其他目录执行 `npm install --prefix`，会把主仓库误注册成 `file:` 依赖写进目标目录的 package.json/lock。必须在目标目录内执行。
- **worktree 场景**：worktree 没有本地依赖，提交钩子（commitlint/lint-staged）需要先在 worktree 根目录 `npm install`；构建验证用 `gradlew.bat -p <worktree> :app:compileAppMaxDebugKotlin`。

## 九、快速复现清单

1. `gradlew :app:assembleAppMaxDebug` + `adb install -r`
2. 启动应用（显式 MainActivity）→ UI 自动化开「Web 服务」开关
3. `adb forward tcp:1122 tcp:1122` → curl 连通性检查
4. curl 走一遍目标接口（含失败路径）+ zip 解压校验
5. 内置浏览器打开网页 → domSnapshot → 模拟点击 → 校验下载/渲染结果
6. UI 树验证书架/界面效果 + logcat 验证 Toast + adb 验证落盘

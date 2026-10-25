# 评教功能开发与验证

本次改动以原仓库 main 的 `f9ea529`（v0.3.2）为基线。

## Android skills 的应用范围

参考 Google 官方 [android/skills](https://github.com/android/skills/tree/4ebabf2b0fd6c04b7150023c8b612f80592a6226)：

- `jetpack-compose/jetpack-compose-performance` 及 `references/lazy-layouts.md`：评教页 LazyColumn 使用可保存的稳定 key 和 contentType，继续在页面统一收集 StateFlow；不为含集合的状态模型添加稳定性注解。
- `testing/testing-setup`：沿用现有 JUnit4、kotlinx-coroutines-test；抽取 EvalDataSource 接口，在 JVM 测试中使用 fake，避免调用真实学校接口。此次只应用与功能相关的单元测试建议，不扩展为整个项目的测试框架迁移。

评分、必答题校验和提交答案构造统一放在 EvalFormLogic 中，单份表单和批量操作共享规则。EvalBatchRunner 负责顺序、刷新、跳过和停止，ViewModel 负责状态与生命周期。

## 验证命令

需要 JDK 17、Android SDK platform 36 和 build-tools 36.0.0：

```sh
cd kcb-app
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

GitHub Actions 的 Android checks 执行同样检查并保存报告和 debug APK。测试不连接真实账号，不会代用户提交评价。

## 回归范围

- 自动填写成功时无 Snackbar；评分限制、降档候选不足时的失败提示仍有效。
- 普通课程、合讲课程、多个教师、已评父课程下未评教师。
- 所有进行中的打分任务，以及仅当前任务；跳过已结束和不支持的任务类型。
- 暂存答案 ID、非计分必答题、未知题型、服务器状态更新延迟。
- 提交失败不重试、不继续下一项；停止时保留当前请求结果，协程取消正常向上传播。

真机还需验证不同屏幕尺寸、返回与旋转、进度区域和停止按钮。真实学校接口的行为与最终评价状态仍需使用者核对，JVM fake 测试不能证明线上提交成功。

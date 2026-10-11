# LZUapp

第三方兰州大学工具箱 App（非官方）。

## 功能

- 课表：周视图、自定义课程、静默同步、本地缓存
- 校园卡：余额、付款码、充值、账单、宿舍电费
- 查寝打卡：定位 + 拍照签到，一键签到
- 其他：成绩查询、邮箱、校园网自动登录

## 构建

```bash
cd kcb-app
./gradlew :app:assembleDebug
```

需要 JDK 17 和 Android SDK（`local.properties` 里配 `sdk.dir`）。

## 说明

仅供学习研究使用。账号密码只存本机（Keystore 加密），不上传任何服务器。

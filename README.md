# LZUapp

第三方兰州大学工具箱 App（非官方）。

## 功能

- 课表：周视图、自定义课程、静默同步、本地缓存
- 校园卡：余额、付款码、充值、账单、宿舍电费
- 查寝打卡：定位 + 拍照签到，一键签到
- 其他：课程与教师评教、自动填写、成绩查询、邮箱、校园网自动登录

## 构建

```bash
cd kcb-app
./gradlew :app:assembleDebug
```

需要 JDK 17 和 Android SDK（`local.properties` 里配 `sdk.dir`）。高德定位需要在 `kcb-app/local.properties` 配置 `AMAP_API_KEY`，该文件不纳入版本库；公开地图瓦片不需要此配置。

## 使用与免责声明

- 本项目是第三方客户端，与兰州大学官方无关。学校接口变化可能导致功能异常或失效。
- 自动填写和一键操作只用于减少重复步骤。评教、签到等内容提交前请自行核对，避免误提交。
- 本软件按“现状”提供，不附带任何担保。

## 开源协议

Copyright (C) 2026 LYCaikano

本项目以 [GNU General Public License v3.0](LICENSE) 发布，详见 `LICENSE`。

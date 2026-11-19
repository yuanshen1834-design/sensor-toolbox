# 传感器工具箱 (Sensor Toolbox)

一个在 **Android 手机上从零手搓构建** 的原生应用：传感器仪表盘 + 类 RT-Thread 内核管理层 + 离线目标检测 / 点云 + 系统信息面板。

> 整个项目在手机上的 Ubuntu(proot) 容器里编写、编译、打包，不使用 Gradle，也不下载官方 Android SDK 的构建工具链（详见「构建」一节）。

## 功能

| 模块 | 内容 |
| --- | --- |
| 概览 | 环境光、接近距离、指南针（带指北指针）、气泡水平仪 |
| 电池 | 电量 / 电压 / 电流 / 功率 / 温度 / 健康度 / 循环次数 / 充电计数 / 满电容量估算 / 电流曲线 |
| 双卡信号 | 每张 SIM 的运营商、制式、服务状态、等级、dBm、RSRP/RSRQ/SINR、MCC/MNC、SIM 状态 |
| 工具 | 摄像头预览与拍照、手电筒、NFC 读取与 NDEF 写入、扫码（ZXing）、取色器、振动测试 |
| 识别 | 离线目标检测（TFLite + SSD MobileNet int8）+ 中文标签 + 三维绿色点云；本地认不出时自动调用在线视觉模型兜底 |
| 内核 | 类 RT-Thread 的软实时内核：任务调度、设备框架、软定时器、邮箱/信号量/互斥量、节拍抖动统计，全部可在界面上操作 |
| 控制台 | 图形化命令树（母命令 → 子命令），无需手输命令 |

界面为全中文 Material Design 3 深色风格。

## 目录结构

```
AndroidManifest.xml   清单（权限、Activity、版本）
src/com/dsh/sensorkit/
  MainActivity.java   界面与业务逻辑
  Rtos.java           软实时内核（调度器/任务/设备/IPC/定时器）
  SensorHub.java      传感器设备驱动层
  Detector.java       TFLite 推理封装（含中文标签映射）
  Views.java          自绘控件（指南针、水平仪、点云、导航栏、电池曲线）
  Md3.java            MD3 配色与控件构造
  App.java            崩溃日志捕获
res/                  多密度图标
assets/               检测模型 detect.tflite（int8，COCO 80 类）与标签
```

## 构建

本项目**不用 Gradle**，用「javac + aapt + d8 + apksigner」手工打包，`build.sh` 会按顺序执行：

1. `javac` 编译（classpath 为 `android.jar` + zxing + tflite）
2. `d8`（R8 提供的命令行 dexer）生成 `classes.dex`
3. `aapt package` 打包清单、资源、assets
4. 把 `classes.dex` 和 `lib/arm64-v8a/libtensorflowlite_jni.so` 塞进 APK
5. `zipalign` 对齐
6. `apksigner` 签名并校验

需要的本地依赖（不入库，体积大）：

```
sdk/p28/android-9/android.jar      # API 28 的 android.jar
sdk/r8.jar                          # R8/D8
sdk/zxing-core.jar                  # 扫码
sdk/tflite_full.jar                 # TensorFlow Lite Java API（classes + api 两个 AAR 合并）
sdk/tflite_extract/jni/arm64-v8a/libtensorflowlite_jni.so
key.jks                             # 你自己的签名密钥（勿提交）
```

用法：

```bash
bash build.sh          # 输出 build/sensorkit.apk
```

## 设计说明与限制

- **软实时而非硬实时**：函数都跑在 Android 线程上，只能说「优先级 + 短周期轮询 + 抖动统计」，真正的硬实时采样由手机的传感器中枢（SSC）完成。
- **内核是模拟层**：用 Java 线程、软件定时器、对象容器模拟 RT-Thread 的对象模型，不是真的 RTOS 内核。
- **离线检测模型体积小、精度一般**：SSD MobileNet v1 300 int8，COCO mAP 约 21；想更准可替换 `assets/detect.tflite`（代码按输出形状自适应，SSD / EfficientDet 系可直接换）。
- **点云不是真实深度**：由检测框位置与面积推算方位与距离，属于示意级的三维可视化。
- **在线识别需要自备 API Key**：在「识别」页填写，只保存在本机 SharedPreferences。

## License

MIT

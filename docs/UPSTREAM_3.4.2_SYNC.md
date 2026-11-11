# 上游 3.4.2 同步与定制功能清理

上游：Mangi-11/Eta，提交 db0004ca186a5f29d392089b622e0003a44abff1（v3.4.2）。合并使用 `git merge -X theirs upstream/main`，冲突以上游为准。合并前的本地分支保存在 `backup/before-upstream-3.4.2`。

## 改动

- 保留上游 3.4.2 的聊天界面、图片查看器、Markdown、模型配置与许可证更新。
- 补齐逆向能力页面和设置入口的简体、繁体中文资源，导航标题改用资源。
- 删除“编辑作战协议”（用户所称编辑作战计划）的两套主题入口、编辑页、导航路由、专用资源、保存/清空及备份接口。逆向能力页只显示总开关和技能统计。
- 保留逆向技能过滤与既有协议文件的只读装载；总开关继续同时控制主代理和子代理。
- 恢复合并时遗失的聊天文件拖放 Modifier 定义，适配上游新的首页 padding 接口。
- 逆向设置的磁盘读取移入 IO，开关写入串行化并丢弃陈旧结果。
- 更新生图工具已登记能力要求后的两条旧测试断言。

## 验证

- 两轮独立子代理代码审查，主代理逐项复核并修正实际问题。
- Debug 编译、29 项相关单元测试通过（逆向回归 5、提示构建 8、工具策略 7、工具要求 9）。
- `lintDebug` 成功：0 Error，417 Warning、3 Hint；保留报告供排查既有告警。
- XML、资源键、格式占位符及 `git diff --check` 通过；编辑功能路由与资源引用已清理。
- 全量单元测试初次执行 2053 项，1537 通过、516 失败。失败中 511 项为 Robolectric 原生运行库不支持 Linux aarch64，3 项为 Root 下 `File.canWrite()` 权限断言，2 项为上述旧生图预期（已修正且复验通过）。不将这次全测记为全部通过。

## 本地正式包

使用既有本地 release keystore，经环境变量注入签名配置；签名物料不进入仓库。ARM64 主机使用仓库 `scripts/aapt2-compat/aapt2` 适配 aapt2，构建时通过 `-Pandroid.aapt2FromMavenOverride=...` 传入，本机绝对路径不提交到 Gradle 配置。

`assembleRelease` 本地构建成功，版本 3.4.2 / versionCode 2026111101，ARM64 Release，R8 与资源裁剪已启用。

- 安装包：`/storage/emulated/0/Download/Eta-v3.4.2.apk`
- 大小：12,282,764 字节。
- SHA-256：`6ac9087f92d55aebd8242c74edbb1833dbf205fcfcf05d11c9dd4f6224d99ff1`
- APK v2 签名验证通过，证书：`CN=Eta Release, OU=Android, O=linyuanjianying`，RSA 3072。
- 证书 SHA-256：`abe92250f10e6b0fc2516119e826d0d6ad5d28b5addaf631dffb0305baa06b31`。
- ZIP CRC、Manifest、resources.arsc、DEX 及构建元数据验证通过。

未执行安装和设备端交互验收。

# 缺失功能补全交付

已补齐逆向能力统一控制、默认沿用上次角色、独立压缩设置；设置首页新增生图/工具边界直达。
逆向开关控制技能索引、读取、资源读取与协议注入，主代理/子代理共享运行快照；管理页保留完整列表。关闭不会卸载技能，也不是安全沙箱，不能收回已存在于历史中的文本。
默认角色恢复在首条发送前等待，失效/归档角色回退普通会话。
修正记忆设置页关于本地检索的错误隐私文案。

## 后续变更：移除「编辑作战协议」

应用内不再提供作战协议编辑页、设置首页入口和逆向能力页的协议状态组；逆向能力页只保留总开关与技能统计。
运行时注入功能保留：若 `filesDir/prompts/` 下存在用户自行放置的非空 `.md`，开关开启时仍作为独立 system 消息注入；协议仓库/装载侧已改为只读（不再有写入、清空、备份与编辑器订阅接口）。

以下为早期 3.4.0 构建记录；本次 3.4.2 的验证以 [同步记录](UPSTREAM_3.4.2_SYNC.md) 为准。

历史验证：Debug Kotlin 编译成功；ReverseCapabilityRegressionTest 4 项与 AgentPromptBuilderTest 8 项全部通过；assembleRelease 成功；ZIP CRC、Manifest、resources.arsc、APK v2 签名验证通过。未在手机安装或进行端到端交互验证。

安装包：/storage/emulated/0/Download/Eta-v3.4.0-features-complete.apk
大小：16,584,928 字节（16.58 MB），比上一版16,550,780字节增加34,148字节。
SHA256：d2ebe4c361e6225756df22494d583c7569a65c9dfca00662266ad6ac5985a17d
签名：Android Debug，与上一安装包证书一致；并非正式发布签名。
构建使用 scripts/aapt2-compat/aapt2 适配本机ARM64工具链参数。

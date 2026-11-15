# AI 接口与题目讲解

版本：Android 0.3.3 / versionCode 13；2026-11-16。

## 配置

1. 更多 → AI 接口设置。
2. 按服务商控制台填写 HTTPS Base URL、模型 ID、API Key。Base URL 自动补 /chat/completions，也可直接填完整 Chat Completions 地址。不能填写网页聊天地址或携带 key 的查询参数。
3. 测试连接会发送一次简短提示，仍可能产生服务商 API 费用；测试成功后点保存。测试不会自动保存。
4. 提交题目后点击 AI 讲解 / 追问，再点讲解按钮。首次提问带入当前完整材料和作答，后续可追问；每页最多10轮。

接口采用 HTTPS POST、Bearer Authorization、model / messages / stream:false，读取 choices[0].message.content。支持文本字符串或 text 类型的内容数组。不支持仅有 Responses、Claude Messages、Gemini 原生协议的地址，需要服务商提供兼容端点。无硬编码模型名。网络请求连接超时15秒、读取超时90秒；响应上限2MB；对话请求上限180KB。

## 本机数据

- API 配置通过 Android Keystore AES-GCM 加密，以 AtomicFile 保存于 getNoBackupFilesDir，不进入应用备份。
- 密码输入框不参与状态恢复与自动填充，配置对话框禁用系统截图；不会回填已有密钥。
- 只向当前配置的 HTTPS 地址发送密钥；禁止自动重定向，更换地址要求重新填写密钥。
- 错误提示只显示本机编写的信息，不显示服务端错误正文或网络异常中的敏感内容。
- 学习进度重置不清除 AI 配置；清除按钮仅删 AI 配置。对话不写入学习记录，返回退出后不保留；普通界面旋转可在系统实例状态中恢复当前对话，密钥不在该状态里。

## 验证范围

scripts/test_ai_client.py 运行生产 AiClient 与 AiPrompt，使用 JVM 模拟 HTTPS 连接验证：地址及输入限制、POST/鉴权/JSON请求、UTF-8正文、HTTP 3xx/400/401/402/403/404/429/500/503、超时、坏JSON、无正文、过大响应、取消和材料字段白名单。不会请求真实付费 API。

构建验收使用 npm.cmd run build:apk 和 gradle -p android-app lintDebug，并核对版本与签名。编译和 JVM 模拟不能证明 Android Keystore 在具体手机上的行为、手机网络及用户真实服务商账户可用；发布时没有用户真实密钥，因此真实付费接口与手机完整流程仍需安装后验证。

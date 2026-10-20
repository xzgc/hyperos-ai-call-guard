# HyperOS 3 AI 通话逆向记录

## 设备基线

- 设备：Xiaomi K90 Pro Max，型号 `25102RK69C`，代号 `myron`
- 系统：HyperOS `OS3.0.306.0.WPMCNXM`
- Android：16
- Root：KernelSU（`su` 上下文为 `u:r:ksu:s0`）

## 核心包

| 包名 | 作用 |
| --- | --- |
| `com.xiaomi.aiasst.service` | AI 通话总控，版本 6.1.6，`sharedUserId=android.uid.system` |
| `com.android.incallui` | 系统来电界面与来电回调 |
| `com.android.phone` / `com.xiaomi.phone` | 电话与 Telephony 服务 |
| `com.miui.yellowpage` | 黄页号码归属与防骚扰分类 |
| `com.miui.voiceassist` | 小爱语音与语音交互入口 |

## 系统原生链路

1. `InCallUI` 收到来电后调用 `AiAssistantUtils.awakeAi()`。
2. 若全局设置 `com.xiaomi.aiasst.call.autoAnswer=1`，`InCallUI` 向 `com.xiaomi.aiasst.service` 发送 `com.xiaomi.aiasst.service.incall_ring`，附带 `incoming_number`。
3. `PhoneRingReceiver` 将号码交给 `AutoPickupManager`。
4. `AutoPickupManager` 查询黄页分类，读取“防漏接”和“防骚扰”设置，决定是否延时接听。
5. 命中后调用 `InCallAiCallService.i()`，通过 `ACTION_AUTO_PICKUP_USE` 启动小爱 AI 通话。

## 手动唤起入口

`UseAiCallReceiver` 暴露了以下动作，并受签名权限保护：

- `com.xiaomi.aiasst.service.use_ai_call`
- `com.xiaomi.aiasst.service.use_ai_call_dial`
- `com.xiaomi.aiasst.service.exit_ai_call`
- `com.xiaomi.aiasst.service.set_incall_voice_control`

接收器会读取：

- `incoming_number`：来电号码
- `ai_call_mode`：`1` 手动、`2` 自动应答、`3` 字幕、`5` 留言机

`UseAiCallReceiver` 命中 `use_ai_call` 后调用 `InCallAiCallService.C(context, number, mode)`，最终进入 `ACTION_USE_AI_CALL`，与系统手动点击 AI 通话走同一条链路。

测试确认：以 root 身份显式广播 `use_ai_call` 可以越过签名权限，AI 服务会实际接收并开始查询号码。日志中出现：

```text
AICall_UseAiCallReceiver: UseAiCallReceiver onReceive:com.xiaomi.aiasst.service.use_ai_call
YellowPage.YellowPageProvider: query(): URI_PHONE_LOOKUP_ITEM from calling package com.xiaomi.aiasst.service
```

## 现有自动接听设置

AI 通话设置保存在：

`/data/user/0/com.xiaomi.aiasst.service/shared_prefs/setting.xml`

关键项：

- `auto_pickup_toggle=true`
- `auto_pickup_unknown_number=false`
- `phone_tag_ids=[...]`
- 防骚扰执行时间保存在同一应用的 `com.xiaomi.aiasst.service_preferences.xml` 的 `antiSpam`
- 防漏接执行时间保存在 `missCall`

设备当前黄页分类 ID：

| cid | 分类 |
| --- | --- |
| 1 | 高风险 |
| 2 | 房产中介 |
| 3 | 广告推销 |
| 5 | 快递外卖 |
| 6 | 贷款推销 |
| 13 | 教育培训 |
| 14 | 装修维修 |
| 21 | 保险理财 |

当前设备只勾选了 `2,3,6,13,14,1,21`，没有勾选 `5`（快递外卖）和“其他陌生号码”。这解释了为什么部分快递外卖或座机来电没有自动进入 AI 通话。

## 黄页数据库

数据库：

`/data/user/0/com.miui.yellowpage/databases/yellowpage.db`

关键表：

- `phone_lookup`：号码、标签、黄页名称、`atd_category_id`
- `antispam_number`：防骚扰号码与 `cid`
- `antispam_category`：分类 ID 与中英文名称

可直接通过 root 查询单个号码：

```powershell
adb shell su -c "content query --uri content://miui.yellowpage/phone_lookup/95555 --projection number:tag:yellow_page_name:atd_category_id"
```

实测 `95555` 返回 `招商银行`。设备数据库中还包括 `4008895558`（中信银行）、`0755-36563176`（平安银行）等银行号码，以及美团外卖、京东物流、德邦快递等 `cid=5` 号码。

## 为什么选择 root 广播

备选方案比较：

- 修改系统 APK：需要平台签名，不可行。
- Xposed/LSPosed Hook：能精确 Hook `InCallUI`，但需要额外 root 模块和重启，故障面更大。
- 无障碍点击“AI 通话”按钮：依赖界面结构，系统改版容易失效。
- root 读取 TelephonyRegistry + 广播：不改系统应用，调用的是系统自己的 AI 通话入口，兼容性最好。

最终实现采用最后一种。

## 应用实现

- `GuardService`：前台服务，注册 `TelephonyCallback` 与 `PHONE_STATE` 广播。
- `TelephonyReader`：root 读取 `dumpsys telephony.registry`，解析 `mCallState=1` 与 `mCallIncomingNumber`。
- `CallerIdResolver`：root 查询小米黄页。
- `RuleEngine`：座机、服务号、快递外卖、推销、银行与紧急号码判定。
- `RootShell`：执行受限的 `su -c` 命令。
- `MainActivity`：规则开关、状态、测试、日志、系统入口。
- `TestConnectionService`：自管理测试来电，用于检查来电界面。

## 配置接管与回退

启用规则引擎时：

1. 读取 `settings get global com.xiaomi.aiasst.call.autoAnswer`。
2. 保存原值到应用私有 SharedPreferences。
3. 写入 `0`，关闭系统内置自动接听。

停用时把原值写回，并清除备份标记。

## 已知限制

- AI 通话本身依赖网络；系统在无网时可能进入留言机模式或直接失败。
- `telephony.registry` 在极少数机型或 SELinux 策略下可能隐藏来电号码；应用会记录“号码读取失败”而不会误接听。
- 银行识别依赖小米黄页名称。黄页没有收录、且号码同时不是座机/服务号的银行手机号码，需要后续补充规则。
- 应用不处理双卡路由选择，由系统 Telecom 与 AI 通话服务自行决定当前来电卡。
- 紧急号码固定排除，避免 AI 接听阻断紧急联络。

## 1.1.0 增强与最近漏接复盘

设备最近两通客服来电：

| 号码 | 时间 | 结果 |
| --- | --- | --- |
| `130****2433` | 2026-10-20 12:02:02 | 来电 3 秒，未接 |
| `028****4175` | 2026-10-20 12:09:50 | 来电 4 秒，未接 |

`028` 号码是成都座机，现有座机规则可以命中；当时助手尚未启用。`130` 号码是标准联通手机号，小米黄页本地与云端查询都没有业务标签，因此无法仅凭号码确认它是客服。为覆盖这种号码，1.1.0 增加了“未识别手机号兜底”开关，默认开启，但联系人号码会跳过。

公开搜索结果普遍将移动通信转售（虚拟运营商）号段列为 `162`、`165`、`167`、`170`、`171`。代码另外把以下非三大基础运营商或专用号段加入虚拟/专用规则：

- `174`：卫星通信相关
- `1349`：卫星通信相关
- `192`：中国广电
- `140`、`141`、`144`、`146`、`147`、`148`、`149`：物联网或数据专用

注意：`166`、`173`、`175`、`176`、`177`、`178` 等属于常规运营商号段，不应错误标记为虚拟运营商；它们只在“未识别手机号兜底”开启且不是联系人时进入 AI 通话。

参考搜索结果：

- [百度：虚拟运营商号段 170 171 162 165 167](https://m.baidu.com/s?word=%E8%99%9A%E6%8B%9F%E8%BF%90%E8%90%A5%E5%95%86%E5%8F%B7%E6%AE%B5%20170%20171%20162%20165%20167)

## 1.1.0 性能

- 黄页号码与联系人查询并行。
- 联系人首次进入后台缓存，之后不再逐个查通讯录。
- 服务启动时预热黄页 Provider。
- 在设备测试中，虚拟号规则命中约 `1.4s`，缓存预热后的陌生手机号兜底约 `0.4s`，包含测试服务启动和日志读取的 ADB 开销。

## 1.2.0 通讯录白名单与手动黑名单

规则优先级调整为：

1. 紧急号码排除。
2. 手动黑名单命中，立即交给小爱，即使号码同时存在于通讯录。
3. 通讯录号码直接放行，不做任何 AI 接听判断。
4. 现有快递、银行、推销、客服、虚拟号、座机、陌生手机号等规则。
5. 普通号码按现有设置处理。

通讯录缓存：

- 使用 `ContactsContract.CommonDataKinds.Phone` 一次性建立号码到姓名的映射。
- 监听 `ContactsContract.Contacts` 与 `CommonDataKinds.Phone` 的内容变化。
- `GuardService` 每 10 分钟强制刷新一次，避免 HyperOS 漏发变更通知。
- 应用无 `READ_CONTACTS` 权限时，来电路径通过 root 查询联系人，白名单仍可生效。

手动黑名单：

- 号码与备注保存在应用私有 SharedPreferences 的 JSON 中。
- 通讯录号码允许加入黑名单，并在列表中显示“通讯录联系人：姓名”标识。
- 黑名单规则优先于通讯录白名单，冲突时强制交给小爱。
- 黑名单是“强制 AI 接听”，不是挂断或系统级拦截。

## 1.3.0 验证状态

- `1.3.0` APK 已编译，`versionCode=4`，通过 APK Signature Scheme v2 校验。
- `1.3.0` 已覆盖安装到 K90 Pro Max，前台服务运行中，系统自动接听设置为 `0`。
- 通讯录白名单真机测试通过：
  - 联系人 A / `170****1606` 未加入黑名单时，跳过虚拟运营商规则，直接放行。
  - 联系人 B / `010****9739` 跳过座机规则，直接放行。
  - 联系人 C / `136****6665` 跳过陌生手机号兜底，直接放行。
- 黑名单与通讯录冲突测试通过：
  - 将通讯录号码 `170****1606` 加入黑名单。
  - 黑名单列表显示“通讯录联系人：联系人 A”标识。
  - 来电判定命中“手动黑名单”，不再被通讯录白名单放行。
  - 删除黑名单后，同一号码恢复“通讯录白名单，直接放行”。
  - 测试完成后黑名单存储恢复为 `[]`。
- 非联系人黑名单 `199****8888` 也已测试命中“手动黑名单”，测试后删除。
- 联系人变更监听和 10 分钟强制刷新已接入 `GuardService`。

## 验证状态

- 已确认 HyperOS 3 设备、包版本、权限、服务与调用链。
- 已确认 root 广播可触发 AI 通话服务。
- 已确认黄页查询接口与分类映射。
- APK 已编译并通过 APK Signature Scheme v2 校验。
- 1.1.0 已安装到 K90 Pro Max，前台服务运行，系统自动接听设置已接管为 `0`。
- 真机规则测试通过：
  - `130****2433` → 陌生手机号兜底，将自动接听。
  - `028****4175` → 座机，将自动接听。
  - `17012345678` → 虚拟运营商，将自动接听。
  - `136****6665` → 识别为联系人，跳过兜底。
  - `110` → 紧急号码，跳过。
- 规则处理测试延迟约 `0.4～1.4 秒`，包含 ADB 启动测试服务和读取日志的开销。
- 两通真实漏接发生在助手启用之前；真实来电的最终端到端验证仍需在下一次客服来电时观察。

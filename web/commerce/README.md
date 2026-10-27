# Carrot搭子官网与投屏会员服务

这个全新 Flask 服务独立于旧的 `web/app.py`。它使用 SQLite，提供产品官网、使用指南、设备授权 API、Z-Pay 下单和支付回调处理。

## 1Panel 部署

1. 在 1Panel 中创建 Python 应用，代码目录选择 `web/commerce`，Python 版本选择 3.11 或更高。
2. 安装依赖：`pip install -r requirements.txt`。
3. 设置启动命令：

   ```text
   gunicorn --workers 2 --bind 0.0.0.0:8000 commerce_app:app
   ```

4. 在 1Panel 的**挂载**页创建持久化目录映射：将主机目录（例如
   `/home/teslatools/commerce-data`）映射到容器目录 `/app/data`。创建主机目录并确保
   1Panel 应用运行用户有读写权限。`DATABASE_PATH` 必须填写**容器目录**，而不是主机目录。
5. 在 1Panel 的**环境变量**中设置：

   | 变量 | 必填 | 说明 |
   |---|---|---|
   | `APP_PUBLIC_URL` | 是 | 绑定到该应用的公网 HTTPS 域名，如 `https://vip.example.com` |
   | `DATABASE_PATH` | 是 | 容器中的持久化 SQLite 路径，填写 `/app/data/commerce.db` |
   | `RELEASE_DIRECTORY` | 是 | 容器中的 APK 与发布清单目录，填写 `/app/data/releases` |
   | `ZPAY_PID` | 开启支付时 | Z-Pay 商户 ID |
   | `ZPAY_KEY` | 开启支付时 | Z-Pay 商户密钥 |
   | `ZPAY_SUBMIT_URL` | 否 | 默认 `https://zpayz.cn/submit.php`，用于用户前台表单跳转支付 |
   | `ZPAY_API_URL` | 否 | 默认 `https://zpayz.cn/api.php`，用于支付回调未到达时的服务端补单查询 |
   | `COMMERCE_SESSION_SECRET` | 发布后台需要 | 随机长字符串，用于管理员登录会话 |
   | `COMMERCE_ADMIN_PASSWORD_HASH` | 发布后台需要 | 管理员密码的 Werkzeug 哈希，不填写则发布后台禁用 |

6. 在 1Panel 的“端口”页添加容器端口 `8000`，再将域名 HTTPS 证书配置完成后访问 `/healthz`，应返回 `{"status":"ok"}`。服务首次启动会自动建表。

套餐位于无敏感信息的 `catalog.json`，金额必须使用分为单位的整数。修改后重启应用生效；已创建订单始终保存创建当时的金额。

建议每日备份数据库：

```bash
sqlite3 /opt/carrot-commerce-data/commerce.db ".backup '/opt/backup/carrot-commerce-$(date +%F).db'"
```

## APK 文件管理与手动分发

将主机目录映射到容器 `/app/data` 后，在其中建立 `releases` 目录。网页后台可上传并管理 APK 文件，
也可从此目录手动分发 APK。Android App 不再自动检查更新，`/api/v1/app-update` 接口已移除。

```text
/app/data/releases/
├── CarrotAssistant-v26082901.apk
├── CarrotAssistant-v26090101.apk
└── release.json
```

后台使用 `release.json` 记录当前选中的发布文件及其信息：

- `version_code` 用于发布记录；
- `apk_file` 必须是同目录内的 ASCII 文件名；
- `sha256` 使用 `sha256sum CarrotAssistant-v26090101.apk` 生成；
- `notes` 用于发布记录，不会推送到 App。

APK 下载地址为 `/downloads/<apk_file>`，可手动分享给用户安装。

### 网页发布后台

设置 `COMMERCE_SESSION_SECRET` 和 `COMMERCE_ADMIN_PASSWORD_HASH` 后，访问
`https://你的域名/admin/login`。后台支持上传 APK、填写 `versionCode`、版本名称、每行一条的更新说明、
强制更新开关，并自动计算 SHA-256 和发布更新清单。

生成密码哈希（仅在本地或服务器终端执行；不要把明文密码提交到仓库）：

```bash
python3 -c "from werkzeug.security import generate_password_hash; print(generate_password_hash('替换为强密码'))"
```

管理员页面不在用户官网首页公开链接中；请保存 `/admin/login` 地址并使用强密码。

## API

| Method | Path | 用途 |
|---|---|---|
| `POST` | `/api/v1/devices/register` | 注册安装实例并获得设备令牌 |
| `GET` | `/api/v1/entitlements/projection` | 查询投屏会员权益 |
| `POST` | `/api/v1/orders` | 创建支付订单 |
| `GET` | `/api/v1/orders/{order_no}` | 查询当前设备订单状态 |
| `POST` | `/api/v1/orders/{order_no}/refresh` | 向 Z-Pay 查询订单并补发已完成权益 |
| `POST` | `/api/v1/licenses/transfer-codes` | 在旧手机生成 10 分钟一次性迁移码 |
| `POST` | `/api/v1/licenses/transfer` | 新手机输入迁移码并转移权益 |
| `POST` | `/api/v1/licenses/recovery-code` | 在旧手机生成需要妥善保存的恢复码 |
| `POST` | `/api/v1/licenses/recover` | 丢失旧手机时用恢复码迁移权益 |

App 首次安装生成 128 bit 以上随机 `installation_id`，存入 Android 加密存储。不要使用 IMEI、MAC 地址或 Android ID：它们可能变化、受系统限制或带来额外隐私问题。

设备注册请求：

```json
{"installation_id":"a-stable-random-installation-id"}
```

除注册接口外，Android 必须携带 `Authorization: Bearer <device_token>`。设备令牌只能保存于 Android 加密存储中，不能打印到日志。

创建订单：

```json
{"plan_code":"thirty_days","payment_type":"alipay"}
```

套餐代码：`five_days`、`thirty_days`、`ninety_days`、`yearly`、`lifetime`。当前只支持支付宝：`alipay`。

## 设备与换机策略

- 一张会员许可证同时只绑定**一台活跃设备**；转移成功后旧设备令牌立即失效。
- 正常换机：旧手机登录 App 后生成一次性迁移码；新手机注册后输入该码。迁移码 10 分钟有效、只能使用一次。
- 丢失或损坏旧手机：用户应在旧手机仍可用时生成并离线保存恢复码。新手机用恢复码迁移；恢复迁移每 30 天最多一次，减少恢复码泄漏导致的风险。
- 无旧设备且没有恢复码时，需要人工核验支付订单号后处理；不要仅凭设备 ID 发放或迁移会员。
- `installation_id` 与设备令牌都不是付款凭证；付款记录和许可证才是最终权益来源。

## Z-Pay 安全规则

实现遵循 <https://z-pay.cn/doc.html>：

- App 创建订单后只得到本站的一次性收银台链接；本站以自动提交的 POST 表单跳转至 `submit.php`，Z-Pay 密钥不会下发到 App；
- `return_url` 和 `notify_url` 均使用不带参数的公开 HTTPS 地址，订单编号只放入服务端保存的记录和 `param`；
- 如异步通知未到达，App 可以调用订单 `refresh` API，由服务器以商户密钥向 `api.php?act=order` 查询并严格核对订单；
- 签名参数按 ASCII 名称排序，忽略空值、`sign` 与 `sign_type`，拼接商户密钥后计算小写 MD5；
- 只以 `notify_url` 的 `TRADE_SUCCESS` 通知发放权益，浏览器跳转页不发放；
- 回调比对商户 ID、签名、支付方式、订单金额、订单状态和平台订单号；
- 重复回调会直接返回 `success`，不会重复增加会员时长。

不要向客户端、网页、日志或 Git 提交 `ZPAY_KEY`。

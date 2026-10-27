"""Carrot搭子官网、投屏会员订单与设备授权 API。"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import secrets
import tempfile
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from functools import wraps
from pathlib import Path
from typing import Callable, TypeVar

import requests
from flask import Flask, abort, jsonify, redirect, render_template, request, send_from_directory, session, url_for
from flask_sqlalchemy import SQLAlchemy
from werkzeug.security import check_password_hash
from sqlalchemy import select, text

T = TypeVar("T")
ORDER_PENDING = "pending"
ORDER_PAID = "paid"
PAYMENT_TYPES = {"alipay"}
TRANSFER_CODE_TTL_MINUTES = 10
RECOVERY_TRANSFER_COOLDOWN_DAYS = 30


@dataclass(frozen=True)
class Plan:
    code: str
    name: str
    price_cents: int
    duration_days: int | None


def utcnow() -> datetime:
    """Use naive UTC so SQLite comparisons remain consistent."""
    return datetime.now(timezone.utc).replace(tzinfo=None)


def price_as_yuan(cents: int) -> str:
    return f"{Decimal(cents) / Decimal(100):.2f}"


def load_plans(path: Path) -> dict[str, Plan]:
    catalog = json.loads(path.read_text(encoding="utf-8"))
    plans: dict[str, Plan] = {}
    for item in catalog.get("plans", []):
        code = item.get("code")
        name = item.get("name")
        price_cents = item.get("price_cents")
        duration_days = item.get("duration_days")
        if not isinstance(code, str) or not code or not isinstance(name, str) or not name:
            raise ValueError("套餐必须有非空 code 和 name")
        if not isinstance(price_cents, int) or price_cents <= 0:
            raise ValueError(f"套餐 {code} 的 price_cents 必须为正整数")
        if duration_days is not None and (not isinstance(duration_days, int) or duration_days <= 0):
            raise ValueError(f"套餐 {code} 的 duration_days 必须为正整数或 null")
        if code in plans:
            raise ValueError(f"套餐 code 重复：{code}")
        plans[code] = Plan(code, name, price_cents, duration_days)
    if not plans:
        raise ValueError("套餐目录不能为空")
    return plans


def load_release_manifest() -> dict | None:
    manifest_path = release_directory / "release.json"
    if not manifest_path.is_file():
        return None
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    apk_file = manifest.get("apk_file")
    version_code = manifest.get("version_code")
    version_name = manifest.get("version_name")
    if (
        not isinstance(version_code, int)
        or version_code <= 0
        or not isinstance(version_name, str)
        or not version_name
        or not isinstance(apk_file, str)
        or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*\.apk", apk_file)
        or not (release_directory / apk_file).is_file()
    ):
        return None
    notes = manifest.get("notes", [])
    if not isinstance(notes, list) or not all(isinstance(note, str) for note in notes):
        return None
    sha256 = manifest.get("sha256", "")
    if sha256 and (not isinstance(sha256, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", sha256)):
        return None
    return {
        "version_code": version_code,
        "version_name": version_name,
        "apk_file": apk_file,
        "notes": notes,
        "sha256": sha256.lower(),
    }


def write_release_manifest(release: dict) -> None:
    manifest_path = release_directory / "release.json"
    with tempfile.NamedTemporaryFile(
        "w",
        encoding="utf-8",
        dir=release_directory,
        prefix=".release-",
        suffix=".tmp",
        delete=False,
    ) as temp_file:
        json.dump(release, temp_file, ensure_ascii=False, indent=2)
        temp_file.write("\n")
        temp_path = Path(temp_file.name)
    temp_path.replace(manifest_path)


def apk_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as apk_file:
        for chunk in iter(lambda: apk_file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def admin_enabled() -> bool:
    return bool(app.config["SECRET_KEY"] and app.config["COMMERCE_ADMIN_PASSWORD_HASH"])


def require_admin() -> None:
    if not admin_enabled():
        abort(503)
    if not session.get("commerce_admin"):
        abort(401)


def require_csrf_token() -> None:
    submitted_token = request.form.get("csrf_token", "")
    if not submitted_token or not hmac.compare_digest(submitted_token, session.get("csrf_token", "")):
        abort(400)


base_dir = Path(__file__).resolve().parent
catalog_path = Path(os.environ.get("CATALOG_PATH", base_dir / "catalog.json")).resolve()
PLANS = load_plans(catalog_path)
database_path = Path(os.environ.get("DATABASE_PATH", base_dir / "commerce.db")).resolve()
database_path.parent.mkdir(parents=True, exist_ok=True)
release_directory = Path(os.environ.get("RELEASE_DIRECTORY", base_dir / "releases")).resolve()
release_directory.mkdir(parents=True, exist_ok=True)

app = Flask(__name__)
app.config.update(
    SQLALCHEMY_DATABASE_URI=f"sqlite:///{database_path}",
    SQLALCHEMY_TRACK_MODIFICATIONS=False,
    SQLALCHEMY_ENGINE_OPTIONS={"connect_args": {"timeout": 10}},
    JSON_AS_ASCII=False,
    APP_PUBLIC_URL=os.environ.get("APP_PUBLIC_URL", "").rstrip("/"),
    ZPAY_PID=os.environ.get("ZPAY_PID", "").strip(),
    ZPAY_KEY=os.environ.get("ZPAY_KEY", "").strip(),
    ZPAY_SUBMIT_URL=os.environ.get("ZPAY_SUBMIT_URL", "https://zpayz.cn/submit.php").strip(),
    ZPAY_API_URL=os.environ.get("ZPAY_API_URL", "https://zpayz.cn/api.php").strip(),
    SECRET_KEY=os.environ.get("COMMERCE_SESSION_SECRET", ""),
    COMMERCE_ADMIN_PASSWORD_HASH=os.environ.get("COMMERCE_ADMIN_PASSWORD_HASH", ""),
)
db = SQLAlchemy(app)


class License(db.Model):
    __tablename__ = "commerce_licenses"

    id = db.Column(db.Integer, primary_key=True)
    reference = db.Column(db.String(32), nullable=False, unique=True, index=True)
    recovery_code_hash = db.Column(db.String(64), nullable=True, unique=True)
    expires_at = db.Column(db.DateTime, nullable=True)
    is_lifetime = db.Column(db.Boolean, nullable=False, default=False)
    last_recovery_transfer_at = db.Column(db.DateTime, nullable=True)
    created_at = db.Column(db.DateTime, nullable=False, default=utcnow)
    updated_at = db.Column(db.DateTime, nullable=False, default=utcnow, onupdate=utcnow)


class Device(db.Model):
    __tablename__ = "commerce_devices"

    id = db.Column(db.Integer, primary_key=True)
    installation_id = db.Column(db.String(128), nullable=False, unique=True, index=True)
    token_hash = db.Column(db.String(64), nullable=False)
    license_id = db.Column(db.Integer, db.ForeignKey("commerce_licenses.id"), nullable=True, index=True)
    is_active = db.Column(db.Boolean, nullable=False, default=True)
    created_at = db.Column(db.DateTime, nullable=False, default=utcnow)
    last_seen_at = db.Column(db.DateTime, nullable=False, default=utcnow)


class Order(db.Model):
    __tablename__ = "commerce_orders"

    id = db.Column(db.Integer, primary_key=True)
    order_no = db.Column(db.String(32), nullable=False, unique=True, index=True)
    device_id = db.Column(db.Integer, db.ForeignKey("commerce_devices.id"), nullable=False, index=True)
    license_id = db.Column(db.Integer, db.ForeignKey("commerce_licenses.id"), nullable=True, index=True)
    plan_code = db.Column(db.String(32), nullable=False)
    amount_cents = db.Column(db.Integer, nullable=False)
    payment_type = db.Column(db.String(16), nullable=False)
    status = db.Column(db.String(16), nullable=False, default=ORDER_PENDING, index=True)
    provider_order_no = db.Column(db.String(64), nullable=True, unique=True)
    checkout_token_hash = db.Column(db.String(64), nullable=False, unique=True)
    created_at = db.Column(db.DateTime, nullable=False, default=utcnow)
    paid_at = db.Column(db.DateTime, nullable=True)


class TransferCode(db.Model):
    __tablename__ = "commerce_transfer_codes"

    id = db.Column(db.Integer, primary_key=True)
    code_hash = db.Column(db.String(64), nullable=False, unique=True, index=True)
    license_id = db.Column(db.Integer, db.ForeignKey("commerce_licenses.id"), nullable=False, index=True)
    expires_at = db.Column(db.DateTime, nullable=False)
    used_at = db.Column(db.DateTime, nullable=True)


with app.app_context():
    db.create_all()


def json_error(message: str, status: int):
    return jsonify({"error": message}), status


@app.errorhandler(400)
def bad_request(_):
    return json_error("请求参数无效", 400)


@app.errorhandler(401)
def unauthorized(_):
    return json_error("设备身份验证失败", 401)


@app.errorhandler(404)
def not_found(_):
    return json_error("资源不存在", 404)


def request_json() -> dict:
    body = request.get_json(silent=True)
    if not isinstance(body, dict):
        abort(400)
    return body


def hash_secret(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def current_device() -> Device:
    scheme, _, token = request.headers.get("Authorization", "").partition(" ")
    if scheme.lower() != "bearer" or len(token) < 32:
        abort(401)
    device = db.session.scalar(select(Device).where(Device.token_hash == hash_secret(token)))
    if device is None or not device.is_active:
        abort(401)
    device.last_seen_at = utcnow()
    db.session.commit()
    return device


def device_required(handler: Callable[..., T]) -> Callable[..., T]:
    @wraps(handler)
    def wrapped(*args, **kwargs):
        return handler(current_device(), *args, **kwargs)

    return wrapped

def current_license(device: Device) -> License | None:
    if device.license_id is None:
        return None
    return db.session.get(License, device.license_id)


def license_payload(device: Device) -> dict:
    license_ = current_license(device)
    enabled = bool(
        license_
        and (
            license_.is_lifetime
            or (license_.expires_at is not None and license_.expires_at > utcnow())
        )
    )
    return {
        "feature": "projection",
        "enabled": enabled,
        "license_reference": license_.reference if license_ else None,
        "is_lifetime": bool(license_ and license_.is_lifetime),
        "expires_at": (
            license_.expires_at.isoformat()
            if license_ and license_.expires_at and not license_.is_lifetime
            else None
        ),
    }


def zpay_signature(parameters: dict[str, str], merchant_key: str) -> str:
    """Match Z-Pay's documented MD5 signing algorithm."""
    pairs = [
        f"{key}={value}"
        for key, value in sorted(parameters.items())
        if key not in {"sign", "sign_type"} and value not in (None, "")
    ]
    return hashlib.md5(("&".join(pairs) + merchant_key).encode("utf-8")).hexdigest()


def valid_zpay_callback(parameters: dict[str, str]) -> bool:
    pid = app.config["ZPAY_PID"]
    merchant_key = app.config["ZPAY_KEY"]
    if not pid or not merchant_key or parameters.get("pid") != pid:
        return False
    return hmac.compare_digest(
        parameters.get("sign", "").lower(),
        zpay_signature(parameters, merchant_key),
    )


def new_order_no() -> str:
    return f"NP{utcnow():%y%m%d%H%M%S}{secrets.token_hex(5).upper()}"


def new_license_reference() -> str:
    return f"CT{secrets.token_hex(8).upper()}"


def new_transfer_code() -> str:
    raw = secrets.token_hex(10).upper()
    return f"CT-{raw[:5]}-{raw[5:10]}-{raw[10:15]}-{raw[15:]}"


def public_url(path: str) -> str:
    root = app.config["APP_PUBLIC_URL"]
    if not root:
        raise RuntimeError("APP_PUBLIC_URL must be configured before accepting payments")
    return f"{root}{path}"


def assign_license(license_: License, new_device: Device) -> None:
    for device in db.session.scalars(
        select(Device).where(Device.license_id == license_.id, Device.is_active.is_(True))
    ):
        device.is_active = False
    new_device.license_id = license_.id
    new_device.is_active = True


def grant_license(order: Order) -> License:
    license_ = current_license(db.session.get(Device, order.device_id))
    if license_ is None:
        license_ = License(reference=new_license_reference())
        db.session.add(license_)
        db.session.flush()
        device = db.session.get(Device, order.device_id)
        assign_license(license_, device)
    order.license_id = license_.id
    plan = PLANS[order.plan_code]
    if plan.duration_days is None:
        license_.is_lifetime = True
        license_.expires_at = None
        return license_
    if not license_.is_lifetime:
        now = utcnow()
        license_.expires_at = max(license_.expires_at or now, now) + timedelta(
            days=plan.duration_days
        )
    return license_


def settle_paid_order(order: Order, provider_order_no: str) -> None:
    order.status = ORDER_PAID
    order.provider_order_no = provider_order_no
    order.paid_at = utcnow()
    grant_license(order)


@app.get("/")
def homepage():
    return render_template("index.html", plans=PLANS.values())


@app.get("/guide")
def guide():
    return render_template("guide.html")


@app.get("/api/v1/plans")
def plans():
    return jsonify(
        {
            "plans": [
                {
                    "code": plan.code,
                    "name": plan.name,
                    "price_cents": plan.price_cents,
                    "duration_days": plan.duration_days,
                }
                for plan in PLANS.values()
            ]
        }
    )


@app.get("/downloads/<filename>")
def download_apk(filename: str):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*\.apk", filename):
        abort(404)
    return send_from_directory(release_directory, filename, as_attachment=True)


@app.route("/admin/login", methods=["GET", "POST"])
def admin_login():
    if not admin_enabled():
        return "发布后台尚未配置", 503
    error = None
    if request.method == "POST":
        password = request.form.get("password", "")
        if check_password_hash(app.config["COMMERCE_ADMIN_PASSWORD_HASH"], password):
            session.clear()
            session["commerce_admin"] = True
            session["csrf_token"] = secrets.token_urlsafe(32)
            return redirect(url_for("admin_releases"))
        error = "密码不正确"
    return render_template("admin_login.html", error=error)


@app.post("/admin/logout")
def admin_logout():
    require_admin()
    require_csrf_token()
    session.clear()
    return redirect(url_for("homepage"))


@app.route("/admin/releases", methods=["GET", "POST"])
def admin_releases():
    require_admin()
    if request.method == "POST":
        require_csrf_token()
        apk = request.files.get("apk_file")
        version_code_text = request.form.get("version_code", "").strip()
        version_name = request.form.get("version_name", "").strip()
        notes = [line.strip() for line in request.form.get("notes", "").splitlines() if line.strip()]
        if apk is None or not apk.filename or not apk.filename.lower().endswith(".apk"):
            return render_template(
                "admin_releases.html",
                release=load_release_manifest(),
                files=sorted(path.name for path in release_directory.glob("*.apk")),
                error="请选择 APK 文件",
                csrf_token=session["csrf_token"],
            ), 400
        try:
            version_code = int(version_code_text)
        except ValueError:
            version_code = 0
        if version_code <= 0 or not version_name or not notes:
            return render_template(
                "admin_releases.html",
                release=load_release_manifest(),
                files=sorted(path.name for path in release_directory.glob("*.apk")),
                error="请填写有效版本号、版本名称和至少一条更新说明",
                csrf_token=session["csrf_token"],
            ), 400

        apk_name = f"CarrotAssistant-v{version_code}.apk"
        destination = release_directory / apk_name
        with tempfile.NamedTemporaryFile(
            "wb",
            dir=release_directory,
            prefix=".upload-",
            suffix=".tmp",
            delete=False,
        ) as temp_file:
            apk.save(temp_file)
            temp_path = Path(temp_file.name)
        temp_path.replace(destination)
        release = {
            "version_code": version_code,
            "version_name": version_name,
            "apk_file": apk_name,
            "sha256": apk_sha256(destination),
            "notes": notes,
        }
        write_release_manifest(release)
        return redirect(url_for("admin_releases", published="1"))

    return render_template(
        "admin_releases.html",
        release=load_release_manifest(),
        files=sorted(path.name for path in release_directory.glob("*.apk")),
        published=request.args.get("published") == "1",
        csrf_token=session["csrf_token"],
    )


@app.post("/api/v1/devices/register")
def register_device():
    installation_id = str(request_json().get("installation_id", "")).strip()
    if not 16 <= len(installation_id) <= 128:
        return json_error("installation_id 长度必须在 16 到 128 个字符之间", 400)
    device = db.session.scalar(select(Device).where(Device.installation_id == installation_id))
    raw_token = secrets.token_urlsafe(48)
    if device is None:
        device = Device(installation_id=installation_id, token_hash=hash_secret(raw_token))
        db.session.add(device)
    else:
        device.token_hash = hash_secret(raw_token)
        device.last_seen_at = utcnow()
    db.session.commit()
    return jsonify({"device_token": raw_token, "license": license_payload(device)})


@app.get("/api/v1/entitlements/projection")
@device_required
def projection_entitlement(device: Device):
    return jsonify(license_payload(device))


@app.post("/api/v1/orders")
@device_required
def create_order(device: Device):
    if not app.config["ZPAY_PID"] or not app.config["ZPAY_KEY"]:
        return json_error("支付服务尚未配置", 503)
    body = request_json()
    plan = PLANS.get(body.get("plan_code"))
    payment_type = body.get("payment_type")
    if plan is None or payment_type not in PAYMENT_TYPES:
        return json_error("套餐或支付方式无效", 400)

    checkout_token = secrets.token_urlsafe(32)
    order = Order(
        order_no=new_order_no(),
        device_id=device.id,
        plan_code=plan.code,
        amount_cents=plan.price_cents,
        payment_type=payment_type,
        checkout_token_hash=hash_secret(checkout_token),
    )
    db.session.add(order)
    db.session.commit()
    return jsonify(
        {
            "order_no": order.order_no,
            "status": order.status,
            "amount": price_as_yuan(order.amount_cents),
            "payment_type": payment_type,
            "checkout_url": public_url(
                f"/payments/zpay/checkout/{order.order_no}?token={checkout_token}"
            ),
        }
    ), 201


@app.get("/api/v1/orders/<order_no>")
@device_required
def get_order(device: Device, order_no: str):
    order = db.session.scalar(
        select(Order).where(Order.order_no == order_no, Order.device_id == device.id)
    )
    if order is None:
        abort(404)
    return jsonify(
        {
            "order_no": order.order_no,
            "status": order.status,
            "plan_code": order.plan_code,
            "amount": price_as_yuan(order.amount_cents),
            "paid_at": order.paid_at.isoformat() if order.paid_at else None,
        }
    )


@app.post("/api/v1/orders/<order_no>/refresh")
@device_required
def refresh_order(device: Device, order_no: str):
    order = db.session.scalar(
        select(Order).where(Order.order_no == order_no, Order.device_id == device.id)
    )
    if order is None:
        abort(404)
    if order.status == ORDER_PAID:
        return jsonify({"status": order.status, "license": license_payload(device)})
    try:
        response = requests.get(
            app.config["ZPAY_API_URL"],
            params={
                "act": "order",
                "pid": app.config["ZPAY_PID"],
                "key": app.config["ZPAY_KEY"],
                "out_trade_no": order.order_no,
            },
            timeout=(5, 15),
        )
        response.raise_for_status()
        remote_order = response.json()
    except requests.RequestException:
        return json_error("支付订单查询暂时不可用，请稍后重试", 502)
    except ValueError:
        return json_error("支付平台返回了无效响应", 502)
    if str(remote_order.get("code")) != "1" or str(remote_order.get("status")) != "1":
        return jsonify({"status": order.status, "license": license_payload(device)})
    if (
        remote_order.get("out_trade_no") != order.order_no
        or remote_order.get("money") != price_as_yuan(order.amount_cents)
        or remote_order.get("type") != order.payment_type
        or not remote_order.get("trade_no")
    ):
        return json_error("支付平台订单信息校验失败", 502)

    db.session.execute(text("BEGIN IMMEDIATE"))
    locked_order = db.session.scalar(select(Order).where(Order.id == order.id))
    if locked_order.status != ORDER_PAID:
        settle_paid_order(locked_order, str(remote_order["trade_no"]))
        db.session.commit()
    else:
        db.session.rollback()
    return jsonify({"status": ORDER_PAID, "license": license_payload(device)})


@app.get("/payments/zpay/checkout/<order_no>")
def zpay_checkout(order_no: str):
    checkout_token = request.args.get("token", "")
    order = db.session.scalar(select(Order).where(Order.order_no == order_no))
    if order is None or not hmac.compare_digest(order.checkout_token_hash, hash_secret(checkout_token)):
        abort(404)
    if order.status == ORDER_PAID:
        return render_template("payment_result.html", order_no=order.order_no)

    plan = PLANS[order.plan_code]
    parameters = {
        "name": plan.name,
        "money": price_as_yuan(order.amount_cents),
        "type": order.payment_type,
        "out_trade_no": order.order_no,
        "notify_url": public_url("/payments/zpay/notify"),
        "return_url": public_url("/payments/zpay/return"),
        "pid": app.config["ZPAY_PID"],
        "param": order.order_no,
        "sign_type": "MD5",
    }
    parameters["sign"] = zpay_signature(parameters, app.config["ZPAY_KEY"])
    return render_template(
        "zpay_checkout.html",
        submit_url=app.config["ZPAY_SUBMIT_URL"],
        parameters=parameters,
    )


@app.post("/api/v1/licenses/transfer-codes")
@device_required
def create_transfer_code(device: Device):
    license_ = current_license(device)
    if license_ is None:
        return json_error("当前设备没有可迁移的投屏会员", 409)
    raw_code = new_transfer_code()
    db.session.add(
        TransferCode(
            code_hash=hash_secret(raw_code),
            license_id=license_.id,
            expires_at=utcnow() + timedelta(minutes=TRANSFER_CODE_TTL_MINUTES),
        )
    )
    db.session.commit()
    return jsonify({"transfer_code": raw_code, "expires_in_seconds": TRANSFER_CODE_TTL_MINUTES * 60})


@app.post("/api/v1/licenses/transfer")
@device_required
def transfer_license(device: Device):
    raw_code = str(request_json().get("transfer_code", "")).strip().upper()
    transfer = db.session.scalar(
        select(TransferCode).where(TransferCode.code_hash == hash_secret(raw_code))
    )
    if transfer is None or transfer.used_at is not None or transfer.expires_at <= utcnow():
        return json_error("迁移码无效或已过期", 400)
    license_ = db.session.get(License, transfer.license_id)
    assign_license(license_, device)
    transfer.used_at = utcnow()
    db.session.commit()
    return jsonify({"license": license_payload(device)})


@app.post("/api/v1/licenses/recovery-code")
@device_required
def create_recovery_code(device: Device):
    license_ = current_license(device)
    if license_ is None:
        return json_error("当前设备没有可恢复的投屏会员", 409)
    raw_code = new_transfer_code()
    license_.recovery_code_hash = hash_secret(raw_code)
    db.session.commit()
    return jsonify({"recovery_code": raw_code})


@app.post("/api/v1/licenses/recover")
@device_required
def recover_license(device: Device):
    raw_code = str(request_json().get("recovery_code", "")).strip().upper()
    license_ = db.session.scalar(
        select(License).where(License.recovery_code_hash == hash_secret(raw_code))
    )
    if license_ is None:
        return json_error("恢复码无效", 400)
    now = utcnow()
    if (
        license_.last_recovery_transfer_at is not None
        and license_.last_recovery_transfer_at > now - timedelta(days=RECOVERY_TRANSFER_COOLDOWN_DAYS)
    ):
        return json_error("恢复迁移冷却中，请使用旧设备生成迁移码或联系支持", 429)
    assign_license(license_, device)
    license_.last_recovery_transfer_at = now
    db.session.commit()
    return jsonify({"license": license_payload(device)})


@app.get("/payments/zpay/notify")
def zpay_notify():
    parameters = request.args.to_dict(flat=True)
    if not valid_zpay_callback(parameters):
        return "invalid signature", 400
    if parameters.get("trade_status") != "TRADE_SUCCESS":
        return "ignored"

    # SQLite has no row-level locks. This serializes the short grant sequence.
    db.session.execute(text("BEGIN IMMEDIATE"))
    order = db.session.scalar(
        select(Order).where(Order.order_no == parameters.get("out_trade_no", ""))
    )
    if order is None:
        db.session.rollback()
        return "unknown order", 404
    if order.status == ORDER_PAID:
        db.session.rollback()
        return "success"
    if order.status != ORDER_PENDING:
        db.session.rollback()
        return "invalid order status", 409
    if parameters.get("type") != order.payment_type:
        db.session.rollback()
        return "payment type mismatch", 400
    if parameters.get("money") != price_as_yuan(order.amount_cents):
        db.session.rollback()
        return "amount mismatch", 400
    provider_order_no = parameters.get("trade_no", "")
    if not provider_order_no:
        db.session.rollback()
        return "missing payment order", 400

    settle_paid_order(order, provider_order_no)
    db.session.commit()
    return "success"


@app.get("/payments/zpay/return")
def zpay_return():
    return render_template("payment_result.html", order_no=request.args.get("order_no", ""))


@app.get("/healthz")
def healthz():
    db.session.execute(select(1))
    return jsonify({"status": "ok"})

import json
import os
import shutil
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
from io import BytesIO
from werkzeug.security import generate_password_hash

TEMP_DIR = tempfile.TemporaryDirectory()
os.environ["DATABASE_PATH"] = os.path.join(TEMP_DIR.name, "commerce-test.db")
os.environ["RELEASE_DIRECTORY"] = os.path.join(TEMP_DIR.name, "releases")
sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from commerce_app import Device, License, Order, app, db, price_as_yuan, zpay_signature


class CommerceApiTest(unittest.TestCase):
    @classmethod
    def tearDownClass(cls):
        with app.app_context():
            db.session.remove()
            db.engine.dispose()
        TEMP_DIR.cleanup()

    def setUp(self):
        app.config.update(
            TESTING=True,
            ZPAY_PID="test-pid",
            ZPAY_KEY="test-key",
            APP_PUBLIC_URL="https://commerce.example",
            SECRET_KEY="test-session-secret",
            COMMERCE_ADMIN_PASSWORD_HASH=generate_password_hash("test-admin-password"),
        )
        self.client = app.test_client()
        with app.app_context():
            db.drop_all()
            db.create_all()
        release_directory = os.environ["RELEASE_DIRECTORY"]
        shutil.rmtree(release_directory, ignore_errors=True)
        os.makedirs(release_directory)

    def register_device(self):
        response = self.client.post(
            "/api/v1/devices/register",
            json={"installation_id": "stable-installation-id-0001"},
        )
        self.assertEqual(response.status_code, 200)
        return response.get_json()["device_token"]

    def test_paid_callback_grants_projection_entitlement_once(self):
        token = self.register_device()
        with app.app_context():
            device = db.session.scalar(db.select(Device))
            order = Order(
                order_no="NPTEST0001",
                device_id=device.id,
                plan_code="thirty_days",
                amount_cents=500,
                payment_type="alipay",
                checkout_token_hash="a" * 64,
            )
            db.session.add(order)
            db.session.commit()

        callback = {
            "pid": "test-pid",
            "name": "Carrot搭子投屏畅享 30 天",
            "money": price_as_yuan(500),
            "out_trade_no": "NPTEST0001",
            "trade_no": "ZPAYTEST0001",
            "trade_status": "TRADE_SUCCESS",
            "type": "alipay",
            "sign_type": "MD5",
        }
        callback["sign"] = zpay_signature(callback, "test-key")
        first = self.client.get("/payments/zpay/notify", query_string=callback)
        duplicate = self.client.get("/payments/zpay/notify", query_string=callback)
        entitlement = self.client.get(
            "/api/v1/entitlements/projection",
            headers={"Authorization": f"Bearer {token}"},
        )

        self.assertEqual(first.data, b"success")
        self.assertEqual(duplicate.data, b"success")
        self.assertTrue(entitlement.get_json()["enabled"])

    def test_callback_with_wrong_amount_is_rejected(self):
        self.register_device()
        with app.app_context():
            device = db.session.scalar(db.select(Device))
            db.session.add(
                Order(
                    order_no="NPTEST0002",
                    device_id=device.id,
                    plan_code="thirty_days",
                    amount_cents=500,
                    payment_type="alipay",
                    checkout_token_hash="b" * 64,
                )
            )
            db.session.commit()

        callback = {
            "pid": "test-pid",
            "money": "0.01",
            "out_trade_no": "NPTEST0002",
            "trade_no": "ZPAYTEST0002",
            "trade_status": "TRADE_SUCCESS",
            "type": "alipay",
            "sign_type": "MD5",
        }
        callback["sign"] = zpay_signature(callback, "test-key")
        response = self.client.get("/payments/zpay/notify", query_string=callback)

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.data, b"amount mismatch")

    def test_product_pages_are_available(self):
        self.assertEqual(self.client.get("/").status_code, 200)
        self.assertEqual(self.client.get("/guide").status_code, 200)
        self.assertEqual(self.client.get("/api/v1/app-update").status_code, 404)

    def test_admin_upload_publishes_release_manifest_and_apk(self):
        login = self.client.post(
            "/admin/login",
            data={"password": "test-admin-password"},
        )
        self.assertEqual(login.status_code, 302)
        with self.client.session_transaction() as client_session:
            csrf_token = client_session["csrf_token"]
        response = self.client.post(
            "/admin/releases",
            data={
                "csrf_token": csrf_token,
                "apk_file": (BytesIO(b"release apk"), "ignored-original-name.apk"),
                "version_code": "26090102",
                "version_name": "v26090102",
                "notes": "更新会员购买页面\n修复投屏稳定性",
            },
            content_type="multipart/form-data",
        )

        self.assertEqual(response.status_code, 302)
        manifest_path = os.path.join(os.environ["RELEASE_DIRECTORY"], "release.json")
        with open(manifest_path, encoding="utf-8") as manifest_file:
            manifest = json.load(manifest_file)
        self.assertEqual(manifest["apk_file"], "CarrotAssistant-v26090102.apk")
        self.assertEqual(manifest["notes"], ["更新会员购买页面", "修复投屏稳定性"])
        self.assertNotIn("mandatory", manifest)

    def test_one_time_transfer_deactivates_old_device(self):
        old_token = self.register_device()
        with app.app_context():
            old_device = db.session.scalar(db.select(Device))
            license_ = License(reference="CTTEST0001", is_lifetime=True)
            db.session.add(license_)
            db.session.flush()
            old_device.license_id = license_.id
            db.session.commit()

        transfer_code = self.client.post(
            "/api/v1/licenses/transfer-codes",
            headers={"Authorization": f"Bearer {old_token}"},
        ).get_json()["transfer_code"]
        new_token = self.client.post(
            "/api/v1/devices/register",
            json={"installation_id": "stable-installation-id-0002"},
        ).get_json()["device_token"]
        response = self.client.post(
            "/api/v1/licenses/transfer",
            headers={"Authorization": f"Bearer {new_token}"},
            json={"transfer_code": transfer_code},
        )

        self.assertTrue(response.get_json()["license"]["enabled"])
        old_device_request = self.client.get(
            "/api/v1/entitlements/projection",
            headers={"Authorization": f"Bearer {old_token}"},
        )
        self.assertEqual(old_device_request.status_code, 401)

    def test_checkout_posts_signed_form_to_zpay(self):
        token = self.register_device()
        with patch("commerce_app.requests.post") as payment_request:
            response = self.client.post(
                "/api/v1/orders",
                headers={"Authorization": f"Bearer {token}"},
                json={"plan_code": "thirty_days", "payment_type": "alipay"},
            )
        payment_request.assert_not_called()
        checkout_url = response.get_json()["checkout_url"]
        checkout = self.client.get(checkout_url)

        self.assertEqual(response.status_code, 201)
        self.assertEqual(checkout.status_code, 200)
        self.assertIn(b"https://zpayz.cn/submit.php", checkout.data)
        self.assertIn(b'name="sign"', checkout.data)

    def test_refresh_settles_verified_paid_order(self):
        token = self.register_device()
        with app.app_context():
            device = db.session.scalar(db.select(Device))
            db.session.add(
                Order(
                    order_no="NPTEST0003",
                    device_id=device.id,
                    plan_code="thirty_days",
                    amount_cents=500,
                    payment_type="alipay",
                    checkout_token_hash="c" * 64,
                )
            )
            db.session.commit()
        provider_response = Mock()
        provider_response.json.return_value = {
            "code": 1,
            "status": 1,
            "out_trade_no": "NPTEST0003",
            "money": "5.00",
            "type": "alipay",
            "trade_no": "ZPAYTEST0003",
        }
        provider_response.raise_for_status.return_value = None
        with patch("commerce_app.requests.get", return_value=provider_response):
            response = self.client.post(
                "/api/v1/orders/NPTEST0003/refresh",
                headers={"Authorization": f"Bearer {token}"},
            )

        self.assertEqual(response.get_json()["status"], "paid")
        self.assertTrue(response.get_json()["license"]["enabled"])


if __name__ == "__main__":
    unittest.main()

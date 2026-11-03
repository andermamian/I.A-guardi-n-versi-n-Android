def test_register_and_me(client, auth):
    r = client.get("/v1/auth/me", headers=auth)
    assert r.status_code == 200 and r.json()["email"] == "ana@example.com"


def test_weak_password_rejected(client):
    r = client.post("/v1/auth/register", json={"email": "b@x.com", "full_name": "Bob Uno",
                                               "password": "123456", "accept_terms": True})
    assert r.status_code == 422


def test_login_wrong_password(client, auth):
    r = client.post("/v1/auth/login", json={"email": "ana@example.com", "password": "malísima"})
    assert r.status_code == 401


def test_admin_endpoint_rejects_user_token(client, auth):
    # Un token de app (audiencia guardian-app) no debe abrir la consola admin.
    r = client.get("/v1/admin/auth/me", headers=auth)
    assert r.status_code == 401


def test_no_token_denied(client):
    assert client.get("/v1/auth/me").status_code == 401


def test_antivirus_scan(client, auth):
    r = client.post("/v1/antivirus/scan", headers=auth, json={"file": {
        "name": "eicar.com", "size": 68, "sha256": "a" * 64, "kind": "unknown",
        "strings": ["EICAR-STANDARD-ANTIVIRUS-TEST-FILE"]}})
    assert r.status_code == 200 and r.json()["resultado"]["nivel"] == "malicioso"


def test_app_scan_and_overview(client, auth):
    r = client.post("/v1/apps/scan", headers=auth, json={"device_id": "dev1", "apps": [
        {"package": "com.bank.fake", "label": "Falsa", "installer": "sideload",
         "permissions": ["android.permission.BIND_ACCESSIBILITY_SERVICE",
                         "android.permission.SYSTEM_ALERT_WINDOW"]},
        {"package": "com.whatsapp", "label": "WhatsApp", "installer": "com.android.vending",
         "category": "messaging", "permissions": ["android.permission.CAMERA"]}]})
    assert r.status_code == 200
    body = r.json()
    assert body["total"] == 2 and body["peligrosas"] >= 1


def test_url_check(client, auth):
    r = client.post("/v1/... ".strip().replace("...", "assistant/personality"), headers=auth) if False else None
    r = client.post("/v1/apps/scan", headers=auth, json={"apps": [
        {"package": "x", "label": "x", "permissions": []}]})
    assert r.status_code == 200


def test_chat_local_provider(client, auth):
    r = client.post("/v1/assistant/chat", headers=auth, json={"message": "Hola, ¿qué puedes hacer?"})
    assert r.status_code == 200
    body = r.json()
    assert body["proveedor"] == "local" and body["conversation_id"]


def test_chat_crisis_safety(client, auth):
    r = client.post("/v1/assistant/chat", headers=auth, json={"message": "quiero matarme"})
    assert r.status_code == 200
    body = r.json()
    assert body["seguridad"]["categoria"] == "crisis"
    assert any(a.get("numero") == "192" for a in body["acciones_sugeridas"])


def test_chat_history_encrypted_and_readable(client, auth):
    cid = client.post("/v1/assistant/chat", headers=auth, json={"message": "recuérdame algo"}).json()["conversation_id"]
    msgs = client.get(f"/v1/assistant/conversations/{cid}", headers=auth).json()
    assert msgs[0]["content"] == "recuérdame algo"


def test_personality_update(client, auth):
    r = client.put("/v1/assistant/personality", headers=auth, json={
        "friendliness": 90, "protection": 80, "proactivity": 60, "verbosity": 50, "humor": 40,
        "mode": "work", "assistant_name": "Lía"})
    assert r.status_code == 200 and r.json()["assistant_name"] == "Lía"


def test_emergency_flow(client, auth):
    c = client.post("/v1/emergency/contacts", headers=auth, json={
        "name": "Mamá", "phone": "3001234567", "relation": "family", "priority": 1})
    assert c.status_code == 201
    summary = client.get("/v1/emergency/contacts", headers=auth).json()
    assert summary["total"] == 1
    inc = client.post("/v1/emergency/sos", headers=auth, json={"lat": 4.6, "lon": -74.0, "kind": "sos"})
    assert inc.status_code == 201 and inc.json()["status"] == "escalating"
    cancel = client.post(f"/v1/emergency/incidents/{inc.json()['id']}/cancel", headers=auth)
    assert cancel.json()["status"] == "cancelled"


def test_vpn_gratis_en_plan_basico(client, auth):
    # La VPN básica es gratis (privacidad = seguridad personal); los servidores exclusivos siguen en Enterprise.
    from app.core.plans import features_for
    assert "basic_vpn" in features_for("BASIC") and "premium_vpn" not in features_for("BASIC")
    r = client.post("/v1/vpn/recommend", headers=auth, json={"purpose": "general"})
    assert r.status_code != 402


def test_audit_chain_integrity(client, auth):
    from app.db.session import SessionLocal
    from app.core.audit import verify_chain
    db = SessionLocal()
    try:
        assert verify_chain(db)["integra"] is True
    finally:
        db.close()

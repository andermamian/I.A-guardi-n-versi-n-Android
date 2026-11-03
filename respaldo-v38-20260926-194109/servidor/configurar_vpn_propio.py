"""Conecta el servidor GuardianIA VPN sin copiar ni pegar (lo usa el bloque de PowerShell de la guía).

Lee los 3 datos de variables de entorno (nunca de la línea de comandos, que otros programas pueden ver):
    GIA_URL, GIA_HUELLA, GIA_FICHA   y opcionalmente GIA_EMAIL (la cuenta dueña del servidor).
Uso:  python configurar_vpn_propio.py
"""
from __future__ import annotations

import os
import sys


def main() -> int:
    url, huella, ficha = (os.environ.get(k, "").strip() for k in ("GIA_URL", "GIA_HUELLA", "GIA_FICHA"))
    if not (url and huella and ficha):
        print("Faltan los datos del servidor (GIA_URL, GIA_HUELLA, GIA_FICHA).")
        return 2
    from app.db.models import User
    from app.db.session import SessionLocal
    from app.local import vpn_propio

    db = SessionLocal()
    try:
        email = os.environ.get("GIA_EMAIL", "").strip().lower()
        usuarios = db.query(User).all()
        if email:
            dueno = next((u for u in usuarios if (u.email or "").lower() == email), None)
        elif len(usuarios) == 1:
            dueno = usuarios[0]
        else:
            dueno = None
        if dueno is None:
            print("CUENTA: indique su correo de GuardianIA (hay varias cuentas o no encontré esa).")
            print("Cuentas en este PC:", ", ".join(sorted(u.email for u in usuarios)) or "ninguna")
            return 3
        dueno_id, dueno_email = dueno.id, dueno.email
    finally:
        db.close()
    try:
        r = vpn_propio.configurar(url, huella, ficha, dueno_id, "PC GuardianIA")
    except vpn_propio.ErrorPropio as e:
        print(f"ERROR: {e}")
        return 1
    print(f"OK: servidor {r.get('region')} ({r.get('pais')}) conectado a la cuenta {dueno_email}. "
          f"Este PC quedó dado de alta en la VPN.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

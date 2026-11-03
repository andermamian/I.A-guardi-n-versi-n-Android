# Sin reglas especiales: la app usa solo APIs públicas y reflexión de JSONObject (estándar).

# Tor integrado (tor-android + jtorctl): usa funciones nativas (JNI) y el protocolo de control de Tor.
# Sin estas reglas, R8 las elimina en release y Tor no arranca.
-keep class org.torproject.jni.** { *; }
-keep class net.freehaven.tor.control.** { *; }
-dontwarn net.freehaven.tor.control.**

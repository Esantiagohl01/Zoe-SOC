# Zoe SOC

App Android sencilla para ver el SOC (y SOH, temperatura y autonomía) de un Renault Zoe fase 1 (ZE40 / R90) en una burbuja flotante o un widget, leyendo con un Vgate iCar Pro 2S. Pensada para correr en el Carlinkit Tbox.

Solo lee datos. No escribe nada en los módulos del carro.

## Qué hace

- Se conecta solo al Vgate por BLE o Bluetooth clásico (prueba ambos).
- Cada 15 s (configurable) pide al módulo de batería (LBC) y al EVC:
  - SOC del tablero (USOC) y SOC real
  - SOH (cada 10 min, cambia muy lento)
  - Temperatura promedio de la batería
  - Corriente
  - Autonomía
- Muestra en una burbuja arrastrable, encima de cualquier app (también AutoKit): un carro, el SOC, 8 barritas como las del tablero y la velocidad (cada ~1 s). Si la tocas, pasa a modo compacto (solo % y barritas).
- También trae un widget, por si el launcher del Tbox los soporta.
- Arranca sola al encender la caja (se puede desactivar).
- Si el carro está apagado, reintenta cada 20 s. Si se pierde el Bluetooth, reconecta cada 10 s.

## Cómo obtener el APK

### Opción A: GitHub (sin instalar nada)
1. Crea un repositorio en GitHub y sube esta carpeta.
2. Ve a la pestaña Actions. El flujo "Compilar APK" corre solo; dura unos 3–5 minutos.
3. Al terminar, descarga el artefacto `ZoeSOC-apk`. Es un .zip con `app-debug.apk` adentro.

### Opción B: Android Studio
1. Abre la carpeta `ZoeSOC` con Android Studio.
2. Espera a que sincronice y luego ve a Build > Build APK(s).
3. El APK queda en `app/build/outputs/apk/debug/`.

## Instalar en el Tbox

1. Copia el APK a una USB o descárgalo desde el navegador de la caja (también sirve `adb install` por WiFi).
2. Permite "instalar apps de origen desconocido" si te lo pide.
3. Abre Zoe SOC y concede los permisos de Bluetooth y notificaciones.
4. Toca "Permitir burbuja flotante" y activa el permiso.
5. Con el carro encendido y el Vgate conectado, toca "Elegir scanner". En BLE suele aparecer como `IOS-Vlink` o `Android-Vlink`.
6. Toca "Iniciar".

Si la caja cierra la app en segundo plano, busca en sus ajustes opciones como autoinicio, "no optimizar batería" o "mantener en segundo plano" para Zoe SOC.

## Primera prueba: compara con el tablero

Los valores vienen de los archivos de campos de CanZE para el Zoe fase 1. Aun así, la primera vez compara el SOC y la autonomía con el tablero. El registro en la parte de abajo de la app muestra cada error (por ejemplo `2101: NO DATA`). Si algo no cuadra, ese texto es lo que hay que revisar.

| Dato | Módulo (pide/responde) | Petición | Bits | Fórmula |
|---|---|---|---|---|
| SOC tablero | 79B / 7BB | 2101 | 288–319 | ×0.0001 |
| Corriente | 79B / 7BB | 2101 | 16–31 | (x−5000)×0.1 |
| SOC real | 79B / 7BB | 2103 | 192–207 | ×0.01 |
| Temp. promedio | 79B / 7BB | 2104 | 600–607 | x−40 |
| SOH | 79B / 7BB | 2161 | 72–79 | ×0.5 |
| Autonomía | 7E4 / 7EC | 223451 | últimos 2 bytes | km |
| Velocidad | trama libre 5D7 (solo escucha) | — | 0–15 | ×0.01 km/h |

## Archivos

- `Transport.kt`: conexión BLE / SPP con el Vgate
- `Elm327.kt`: comandos AT, armado de respuestas multi-trama y extracción de bits
- `ZoeReader.kt`: qué se pide y cómo se convierte
- `ZoeService.kt`: servicio en primer plano que hace el ciclo de lectura
- `Overlay.kt`: burbuja flotante
- `Receivers.kt`: widget y arranque al encender
- `MainActivity.kt`: pantalla de configuración

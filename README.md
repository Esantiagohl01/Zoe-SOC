# Zoe SOC

App Android sencilla para ver el SOC (y SOH, temperatura y autonomía) de un Renault Zoe fase 1 (ZE40 / R90) en una burbuja flotante o un widget, leyendo con un Vgate iCar Pro 2S. Pensada para correr en el Carlinkit Tbox.

Solo lee datos. No escribe nada en los módulos del carro.

## Qué hace

- Se conecta solo al Vgate por BLE o Bluetooth clásico (prueba ambos).
- Burbuja flotante (también encima de AutoKit/CarPlay): carro visto desde arriba con la batería que se llena según la carga, llantas de colores según la presión, faros y stops; SOC con decimal y temperatura de la batería. Cargando: rayo, kW y tiempo estimado al 80 %. Tocándola pasa a modo compacto (solo %), pegada a la derecha.
- Colores: verde ≥ 30 %, amarillo < 30 %, rojo < 20 %.
- App tipo tablero: medidor con SOC y autonomía; en vivo (solo con la app abierta) potencia, velocidad y consumo instantáneo; consumo promedio, temperatura, SOH, batería 12 V, diferencia entre celdas, estado de carga, última carga, gráfica del SOH y presión de llantas en PSI.
- Arranca sola al encender la caja. Si el carro está apagado reintenta cada 20 s; si se pierde el Bluetooth reconecta cada 10 s.

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

| Dato | Origen | Detalle |
|---|---|---|
| SOC tablero / corriente | LBC 79B/7BB · 2101 | bits 288–319 ×0.0001 / 16–31 (x−5000)×0.1 |
| SOC real | LBC · 2103 | 192–207 ×0.01 |
| Temp. promedio | LBC · 2104 | 600–607 x−40 |
| SOH | LBC · 2161 | 72–79 ×0.5 |
| Celdas | LBC · 2141 / 2142 | 16 bits por celda ×0.001 V |
| Autonomía / 12 V | EVC 7E4/7EC · 223451 / 222005 | km / 24–39 ×0.01 V |
| Enchufe, consumo promedio | trama 654 | bit 2 / 52–61 ×0.1 |
| Potencia de carga | trama 42E | 56–63 ×0.3 kW |
| Energía disponible | trama 427 | 49–57 ×0.1 kWh |
| Llantas | trama 673 | ×13.725 mbar → PSI |
| Velocidad / potencia | tramas 5D7 / 1FD | ×0.01 km/h / (x−80) kW |

Las luces no aparecen en los datos de CanZE, así que en el dibujo quedan siempre encendidas.

## Archivos

- `Transport.kt`: conexión BLE / SPP con el Vgate
- `Elm327.kt`: comandos AT, armado de respuestas multi-trama y extracción de bits
- `ZoeReader.kt`: qué se pide y cómo se convierte
- `ZoeService.kt`: servicio en primer plano que hace el ciclo de lectura
- `Overlay.kt`: burbuja flotante
- `Receivers.kt`: widget y arranque al encender
- `MainActivity.kt`: pantalla de configuración

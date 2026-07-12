# CamaraViewer

App Android para ver en vivo la cámara Yi (yi-hack-MStar) y navegar sus grabaciones, pensada para usarse junto con Tailscale.

## Configuración

Antes de compilar, revisa `app/src/main/res/values/strings.xml`:

- `camera_ip`: IP local de la cámara (ej. 192.168.1.4)
- `camera_rtsp_port`: puerto RTSP (554 por defecto)
- `camera_http_port`: puerto HTTP del panel/servicios web (80 por defecto)
- `camera_stream_path`: `ch0_0.h264` (alta resolución) o `ch0_1.h264` (baja resolución)
- `camera_user` / `camera_pass`: si le pones autenticación a la cámara más adelante, llena estos dos campos

## Requisito para que funcione desde otro país

El celular debe tener la app de **Tailscale** instalada y **activada** (conectada al mismo tailnet que el router). La app CamaraViewer no reemplaza a Tailscale — se conecta a la IP local de la cámara asumiendo que el túnel ya está activo.

## Cómo se compila

Este repo incluye un workflow de GitHub Actions (`.github/workflows/build.yml`) que compila el APK automáticamente:

1. Sube este proyecto a un repositorio nuevo en GitHub.
2. Ve a la pestaña **Actions** del repo.
3. Espera a que el workflow "Build APK" termine (ícono verde).
4. Entra al run finalizado → sección **Artifacts** → descarga `CamaraViewer-debug-apk`.
5. Descomprime el .zip descargado, ahí está el `app-debug.apk`.
6. Pásalo a tu celular (por USB, Google Drive, Telegram a ti mismo, etc.) e instálalo (activa "Instalar apps de fuentes desconocidas" si Android lo pide).

## Funcionamiento

- Al abrir la app, se conecta directo en pantalla completa al stream RTSP de la cámara.
- Un "watchdog" revisa cada 5 segundos si la imagen sigue avanzando. Si detecta que se congeló (o hay un error de red), destruye y recrea el reproductor automáticamente, reintentando sin intervención.
- Botón en la esquina inferior derecha para ver grabaciones guardadas en la SD de la cámara, organizadas por carpeta/hora, con reproductor propio.

## Si cambias de cámara o de IP en el futuro

Solo edita `strings.xml`, vuelve a subir el cambio (`git push`) a la rama `main`, y Actions compila un APK nuevo automáticamente.

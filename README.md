# Pomi — plugin para Paper 26.2

Port a plugin de servidor del mod **Maine** (Pomi): la ranita compañera vive en el servidor y platica
con Grok desde el servidor, así que los jugadores entran con el cliente vanilla, sin mods.

## Uso en el juego

- Los jugadores empiezan **sin** Pomi. `/p` la llama (la primera vez sale de un color al azar:
  naranja, blanca o verde, que se guarda en el jugador).
- `/p adios` (o `bye`, `chao`, `vete`) la despide: devuelve lo que traía y desaparece hasta el próximo `/p`.
- `/p <lo que sea>` le habla (Grok) y entiende órdenes con sus herramientas.
- Órdenes directas, sin Grok: `seguir [dist]`, `quieta`, `ven`, `ve` (a donde apuntas), `detener`,
  `guardia`, `atacar [mob] [matar]`, `recoger [item]`, `soltar [item]`, `devolver`, `iluminar [n]`,
  `recolectar <recurso> [n]`, `cultivar [cosechar|plantar]`, `quitar [n]`,
  `construir pared <largo> <alto> [atravesada] [item]` / `construir columna <alto> [item]`,
  `craftear <item> [n]`, `baila`, `canta`, `salta`, `saluda`, `croa`, `dame`, `toma`, `color <naranja|blanca|verde>`, `receta <item>`, `bioma <nombre>`,
  `muerte`, `siguiente`, `estado`, `herobrine`, `olvidar`. Admin: `modelo [id]`, `recargar`.
- `baila`, `canta`, `salta`, `saluda`, `croa`, `dame` y `toma` no gastan tokens: Pomi hace la acción y dice
  una de 10 frases al azar. Los kills tampoco: cada mob común tiene 10 frases y el resto usa 20 genéricas.
- `consigue`, `trae`, `junta`, `busca`, `tala`, `mina`... + recurso (`/p consigue 10 madera`) es igual que `recolectar`, sin Grok:
  Pomi busca el recurso a 30 bloques del jugador sin que lo señales; si no hay, dice hacia dónde vio más.
- `craftear` sin cantidad hace 1 (o todos si son tablones, palos o antorchas).
- Clic derecho en Pomi abre su ventana: sus 2 espacios, su espacio de herramienta y un botón Detener.
- Se le dan cosas lanzándoselas con **Q**; una herramienta lanzada va a su espacio de herramienta.

## Instalar

```bash
bash gradlew build       # Java 25; genera build/libs/Pomi-1.2.0.jar
```

Copia `Pomi-1.2.0.jar` a `plugins/` de un servidor **Paper 26.2** (base del proyecto: build #134). Al
arrancar, Paper descarga solo el driver de SQLite (`libraries` en `plugin.yml`).

Para que platique, define la variable de entorno `XAI_API_KEY` en el servidor (recomendado) o
`xai-api-key` en `plugins/Pomi/config.properties`. Sin clave obedece las órdenes directas y avisa
de los eventos con mensajes cortos.

## Voz dentro de Minecraft con Simple Voice Chat

1. Instala el [plugin oficial Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat)
   para tu versión de Paper en `plugins/` y reinicia el servidor. La integración usa la API 2.5.27.
2. Instala el mod Simple Voice Chat compatible con tu versión de Minecraft y tu cargador de mods
   en cada cliente que vaya a usar voz. Configura su micrófono y su tecla para hablar.
3. Abre el puerto **UDP** configurado por Simple Voice Chat (por defecto `24454`), según su
   [guía de servidor](https://modrepo.de/minecraft/voicechat/wiki/server_setup).
4. En `plugins/Pomi/config.properties`, configura `kokoro-url` y `whisper-url` con servicios
   accesibles desde el servidor. Los valores de ejemplo son de una intranet y hay que ajustarlos
   si no tienes acceso a ella. Kokoro debe entregar PCM mono de 16 bits little-endian a 24 kHz;
   Whisper debe aceptar la API `/v1/audio/transcriptions`. Grok sigue usando `XAI_API_KEY`.
5. Deja `simple-voice-chat=true`, usa `/p recargar`, llama a tu rana con `/p` y comprueba `/p voz`.
   A menos de `voz-distancia=16` bloques, habla por Simple Voice Chat: **«Pomi, ven»**,
   **«Pomi, consigue madera»** o **«Pomi, ¿cómo estás?»**. Al hacer una pausa de unos 700 ms,
   Pomi transcribe la frase, ejecuta la orden y pronuncia su respuesta desde la posición de la rana.

Solo escucha al dueño de esa Pomi y solo él oye su respuesta, con volumen según la distancia.
Se ignoran las conversaciones de grupo y las frases que no empiezan con su nombre. Si dices solo
«Pomi», tienes 7 segundos para la siguiente frase; el chat confirma que está escuchando.
Mientras suena su respuesta, no captura tu micrófono para evitar el eco. Cada fragmento admite
hasta 15 segundos. El audio cercano se envía a Whisper para detectar el nombre; la palabra de
activación se reconoce después de transcribir, no localmente en el cliente.

Todas las frases emitidas por Pomi (también las de órdenes por texto y eventos) usan esta salida
cuando estás cerca y conectado a Voice Chat. Las órdenes se ejecutan como el jugador y conservan
los permisos y límites existentes. `voz=false` desactiva únicamente la app de escritorio y su
puerto HTTP; Simple Voice Chat funciona de forma independiente. Sin Voice Chat, el plugin sigue
funcionando por texto y con la app. Si vinculas la app y usas Voice Chat a la vez, oirás ambas salidas.

Diagnóstico: `/p voz estado`. Para vincular la app aunque tengas Voice Chat: `/p voz app`.
Si Whisper o Kokoro fallan, revisa el log del servidor; las respuestas por chat siguen disponibles.

### Verificar la integración al desarrollar

`bash gradlew test` verifica las conversiones de audio y el reconocimiento del nombre. Las dos
pruebas HTTP requieren la simulación local y se omiten cuando no se define `POMI_VOICE_SIM_URL`.
Con Postman CLI 1.70+ (sin publicar ni iniciar sesión), en dos terminales:

```bash
postman simulation run postman/simulations/voice.sim.yaml --port 4900 --no-history
POMI_VOICE_SIM_URL=http://127.0.0.1:4900 bash gradlew build
```

La simulación verifica el formato solicitado a Kokoro, devuelve un segundo de audio PCM sintético
y simula un error 503. El contrato parte de las llamadas Kokoro existentes en este repositorio;
no requiere buscar mocks en una cuenta de Postman. Esto no sustituye la prueba en Minecraft con
Whisper y Kokoro reales, el micrófono, permisos y el puerto UDP de Voice Chat.

## Voz de Pomi (app de escritorio)

El cliente vanilla no puede reproducir audio nuevo, así que la voz va en una app aparte para Windows,
**Pomi Voz** (`Pomi Voz.exe`, un solo archivo, sin instalar nada; se distribuye aparte):

1. En el juego: `/p voz app` muestra la dirección del servidor y un código de 6 letras (vale 5 minutos).
2. En la app: pon la dirección y el código y dale **Vincular**. Queda vinculada aunque se cierre.
3. Todo lo que Pomi te dice (respuestas de Grok y frases fijas) se oye en la app, con su tono de rana.

4. Con **Escuchar** activado, la app oye tu micrófono sin tecla: di **"Pomi, consigue madera"** (también
   vale "oye Pomi, ..." o "hey Pomi, ...") y se ejecuta como `/p consigue madera`. Si solo dices "Pomi", suena
   una campanita y lo siguiente que digas en 7 segundos es la orden. Lo que no empieza con su nombre se
   ignora. Mientras Pomi habla no se oye el micrófono, para que no se oiga a sí misma.
5. O con **tecla para hablar**: en "Tecla" da clic y presiona la tecla o el botón lateral del mouse que
   quieras (Esc = ninguna). Funciona aunque estés en Minecraft: mientras la mantienes graba, y al soltarla
   lo manda como orden, sin decir "Pomi" (y aunque Pomi esté hablando). Puedes usar las dos formas, o
   desmarcar "Con Pomi" para usar solo la tecla. Elige una que el juego no use (p. ej. un botón lateral).

El servidor pasa el audio por Whisper (`whisper-url`, el speaches del mod). `/p voz quitar` desvincula tus apps. El servidor llama a Kokoro (`kokoro-url`), así que los jugadores
solo necesitan llegar al puerto `voz-puerto` (TCP 8765): ábrelo en el firewall.

La app tiene tono, volumen y silencio para la voz de Pomi; para el oído, el micrófono a usar, su
volumen (0–400 %) y un visor: barras grises = ruido, verdes = voz que se va a mandar, azules = Pomi
hablando (ahí no se oye el micrófono), y la línea amarilla es el umbral. Si al hablar las barras no
pasan la línea, sube el volumen del micrófono; si el ruido la pasa solo, bájalo. Se minimiza a la bandeja.

La conversación (lo que dice Pomi, tus órdenes y lo que se oyó) se guarda por día junto al `.exe`, en
`conversacion-AAAA-MM-DD.txt`, con la hora de cada línea. Si el archivo del día ya existe se agrega al
final, nunca se sobreescribe. Si en esa carpeta no se puede escribir, se guarda en `%APPDATA%\PomiVoz`.

**Linux** (y Mac, o Windows con Java): la misma app en Java, en el proyecto `voz-app` (se distribuye aparte, no está en este repositorio). Necesita Java 17+.
`./gradlew :voz-app:linux` arma `voz-app/build/distributions/PomiVoz-linux.zip` con `PomiVoz.jar`,
`pomi-voz.sh` (la abre), `instalar.sh` (la agrega al menú de aplicaciones), `LEEME.txt` y
`empaquetar-jpackage.sh` (se corre en Linux para armar una versión con Java incluido). La tecla para
hablar funciona en X11; en Wayland los programas no pueden leer teclas de otras ventanas, ahí se usa
"Con Pomi". La configuración va en `~/.config/PomiVoz/config.txt`.

Compilar la app de Windows (usa el `csc` que trae Windows, .NET Framework 4):

```powershell
powershell -ExecutionPolicy Bypass -File app\crear-app.ps1
```

## Configuración (`plugins/Pomi/config.properties`)

Ejemplo completo en [config.properties.example](config.properties.example) (sin clave). Cópialo a
`plugins/Pomi/config.properties` o déjalo que el plugin lo genere la primera vez.

| Clave | Por defecto | |
|---|---|---|
| `xai-api-key` | vacío | Si está vacío se usa `XAI_API_KEY`. |
| `model` | `grok-4.20-0309-non-reasoning` | El mismo del mod. `/p modelo <id>` (admin). |
| `personalidad` | vacío | Ajuste extra a la personalidad de Pomi. |
| `memoria` | `true` | Datos por jugador (`recordar_dato`) en `memoria.db`, máx. 40. |
| `comentarios-ia` | `true` | Pomi comenta logros, kills y tareas con Grok (una llamada por comentario). |
| `espera-mensajes` | `2` | Segundos entre mensajes de un jugador. |
| `solo-bloques-naturales` | `true` | Solo rompe tierra, piedra, troncos, minerales... nunca construcciones. |
| `voz` | `true` | Activa el enlace con la app Pomi Voz. |
| `simple-voice-chat` | `true` | Escuchar y responder dentro de Minecraft; requiere Voice Chat en servidor y cliente. |
| `voz-distancia` | `16` | Distancia máxima al dueño para escuchar y responder (1–64 bloques). |
| `voz-puerto` | `8765` | Puerto HTTP para la app. |
| `kokoro-url` | intranet | Servidor Kokoro (`/v1/audio/speech`); el servidor lo llama. |
| `kokoro-voz` | `ef_dora` | Voz de Kokoro. |
| `kokoro-velocidad` | `0.95` | Velocidad de la voz. |
| `whisper-url` | intranet | Whisper (`/v1/audio/transcriptions`) para las órdenes de voz; vacío = sin oído. |
| `whisper-modelo` | `Systran/faster-whisper-small` | Modelo de Whisper. |

## Qué se porteó del mod y qué no

Se porteó (con las reglas del mod): seguir/quedarse/venir/ir, guardia (5 hostiles), atacar (deja a 1
de vida o mata hostiles comunes; nunca jefes, aldeanos ni mascotas), recoger/soltar/atrapar items,
herramienta con tiers, eficiencia, fortuna/toque de seda y desgaste, recolectar (30 bloques del
jugador, 3 de alto, solo bloques a la vista, troncos solo de árboles, minerales con el pico
correcto, huevos), quitar bloques conectados, cultivar el campo completo en espiral desde el centro,
construir paredes/columnas subiéndose a lo construido, crafteo 2x2 (con cadena troncos → tablones →
palos), iluminar (con antorchas propias o crafteadas), encargos de varios pasos, rutinas
(`hacer_acciones`), recetas reales, siguiente logro, dónde moriste, buscar bioma, memoria, comentarios
de logros y kills, y **Herobrine con un Mannequin real** (Paper 26.2 lo expone).

Diferencias por ser plugin:

- **Voz y oído**: dentro del juego con Simple Voice Chat, o en la app de escritorio Pomi Voz (ver arriba).
- **Solo Pomi** (rana). Sin libro ni selector de compañeros.
- Los mixins del mod se reemplazaron por API de Paper: `setAware(false)` en vez de FrogMixin,
  `PlayerAdvancementDoneEvent` en vez del mixin de logros, `Mannequin` de Paper para Herobrine.
- Romper y poner bloques pasa por `BlockBreakEvent`/`BlockPlaceEvent` con el jugador como autor, así
  que respetan WorldGuard, GriefPrevention y la protección del spawn, y los loggers lo registran.
- `ejecutar_comando` (solo creativo) corre el comando **como el jugador, con sus permisos**, nunca
  como consola, y bloquea `op`, `stop`, `ban`, permisos, etc.
- Pomi no se guarda con el mundo: si el servidor se cae no quedan ranas duplicadas.

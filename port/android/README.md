# Ponte Wild Blood

A camada de compatibilidade: o jogo Android de 32 bits Wild Blood (1.1.5, armeabi-v7a, Unreal Engine 3) funciona em telefones de 64 bits sem suporte a AArch32 (validado em OnePlus 13s, Snapdragon 8 Elite) por meio de uma emulação própria da CPU e do redirecionamento da renderização para a GPU real do telefone. O jogo não é distribuído: é necessário ter seu próprio APK e cache.

## Arquitetura

```
  APK com.jamescrumble.wildblood (Java, sem NDK)               Telefone: Oryon (somente AArch64), Adreno 830
  ┌───────────────────────────────────────────────────────┐
  │ MainActivity   SurfaceView + sobreposição (FPS, carga) │
  │                multitouch/botões/gamepad → comandos     │
  │ GameProcess    desempacota sysroot, cache, inicia o qemu, │
  │                ctl/commands, status.json, áudio           │
  │ GlBridge       fluxo wb-gl: EGL + GLES 3.0,       │◄── pipe gl_cmd  (fluxo de comandos, 1 MB)
  │                executa os comandos GL na GPU do telefone │──► pipe gl_reply (respostas a requisições, ack de frame)
  │ PerfHint       sessão ADPF para threads do qemu, telemetria │
  └───────────────────────────────────────────────────────┘
                  │ ProcessBuilder (subprocesso)
                  ▼
  libqemu.so = nosso qemu-arm 11.1.2 (estático para aarch64, patches: float/NEON rápidos, pesquisa rápida de branches)
  ┌───────────────────────────────────────────────────────┐
  │ mundo ARM32 convidado (armhf, traduzido para AArch64) │
  │  bin/wb  — carregador de porta: carrega .so, fake     │
  │             JNI e API Android (EGL, AudioTrack…)         │
  │  sysroot/  — glibc armhf, SDL2 e outros                 │
  │  game/lib/armeabi-v7a/libUnrealEngine3.so — o jogo em si │
  │  game/files — dados (link simbólico para o cache do jogo) │
  └───────────────────────────────────────────────────────┘
```

Canais entre processos:

| item | como funciona |
|---|---|
| gráficos | 2 FIFOs: comandos GL para dentro, respostas às consultas síncronas e confirmações de frame de volta |
| entrada | arquivo de texto `ctl/commands` (`touch 1 x y id`, `key 4 down`, `pad 1`…), o port lê a cada frame |
| estado | `ctl/status.json` (frame, menu/jogo) |
| áudio | o jogo grava em uma classe Java `AudioTrack` por meio de JNI; o port injeta sua própria classe (`game/jni/audio_track.cpp`) → FIFO → `AudioTrack` real do Android |
| configurações | arquivos ao lado do log (`gpu_name.txt`, `engine_env.txt`, `cpu_mask.txt`…) e variáveis de ambiente |

### Caminho da renderização

1. O jogo chama GLES 2.0 (`glDrawElements`, `glUniform…`). Dentro do qemu, essas chamadas não vão para o driver, e sim para os stubs do bridge (`portbase/src/gl_bridge.cpp`, código ARM32).
2. O bridge codifica a chamada em uma mensagem compacta no buffer:
   - os nomes dos objetos (texturas, buffers, shaders) são distribuídos pelo próprio convidado; o host traduz isso para objetos reais;
   - os arrays de vértices do cliente são copiados diretamente para a mensagem de desenho;
   - mudanças repetidas de estado e uniforms idênticos são descartadas antes de enviar.
3. Consultas síncronas (status do shader, `glGet*`, `glReadPixels`) esvaziam o buffer e aguardam resposta.
   Elas são poucas: a informação do programa é coletada em uma única consulta ao linkar.
4. No lado Java, a thread `GlBridge` lê o fluxo de comandos e executa-os via `GLES30` na GPU.
   A renderização ocorre em um `SurfaceView` de tamanho fixo (~1544×720 para manter a proporção da tela),
   e o compositor estica a imagem para a tela inteira.
5. No `swap`, o host mostra o frame e envia confirmação; o convidado pode antecipar o host em no máximo 1 frame.

### Entrada

Para o motor, o telefone é um touchscreen padrão: o jogo desenha seu HUD sensível ao toque, os toques vão para
`GameGLSurfaceView.nativeOnTouch` com os códigos do próprio motor (1 = dedo pressionado, 2 = movimento, 0 = dedo levantado),
`id` do dedo e tempo do evento. "Voltar" é repassado ao jogo (código da tecla 4). A disposição de gamepad para este jogo ainda não foi implementada.

## Instalação

Depois de instalar o APK, coloque os arquivos do jogo na pasta do aplicativo:

```text
/sdcard/Android/data/com.jamescrumble.wildblood/files/
  <APK original do jogo 1.1.5>.apk
  com.gameloft.android.ANMP.GloftWBHM/files/AOWGame/…    cache extraído
  com.gameloft.android.ANMP.GloftWBHM/files/Engine/…
```

As pastas criadas por `adb push` pertencem ao usuário `shell`, e o aplicativo não consegue acessá-las.
Depois de copiar via adb, é necessário abrir:

```sh
adb shell "find /sdcard/Android/data/com.jamescrumble.wildblood/files/com.gameloft.android.ANMP.GloftWBHM -type d -exec chmod 2777 {} +"
```

Se o aplicativo for executado uma vez, ele mesmo cria as pastas `…/GloftWBHM/files/{data,textures}`
 e então os arquivos podem ser simplesmente copiados para lá. O app informa na tela exatamente o que está faltando.

A primeira execução desempacota o sysroot (~100 MB) no armazenamento privado, extrai `libUnrealEngine3.so` do APK do jogo e
conecta o cache por link simbólico, sem copiar tudo.

## Build

```sh
# WSL, a partir da raiz do repositório. Necessário: build/wb (port), Android SDK build-tools 34.0.0 + platform 34,
# JDK 8+ (javac), python3. QEMU_BIN — nosso qemu-arm (ver qemu/README.md); sem ele, usa Debian 7.2.
QEMU_BIN=/path/to/qemu-arm.stripped JAVA_HOME=/path/to/jdk bash android/build_apk.sh
```

Resultado: `WildBlood-Bridge-debug.apk` na pasta `port/`. O ícone é obtido do APK do jogo (`GAME_APK` ou primeiro APK em `gamebin/`). A keystore de depuração está em
`android/build/debug.keystore`, então os APKs reconstruídos são instalados por cima. O patch do qemu, sua build em Docker,
 o teste de precisão do float e o benchmark estão em `qemu/`.

## Configurações e depuração

Tudo fica em `Android/data/com.jamescrumble.wildblood/files/`:

| arquivo | finalidade |
|---|---|
| `wb.log` | log do jogo e do wrapper; a cada 5 s aparece uma linha `[gl] frames …` com FPS, núcleo/frequência do qemu e estatísticas do bridge |
| `gpu_name.txt` | substitui `GL_RENDERER`; por ele o motor escolhe o perfil de qualidade |
| `engine_env.txt` | linhas `KEY=VALUE` no ambiente do port (`WB_GAME_LOG=1`, `WB_GL_PROFILE=1`, …) |
| `cpu_mask.txt` | máscara hexadecimal dos núcleos (por exemplo `c0`): fixa o qemu via `taskset` |
| `resolution.txt` | tamanho do render, por exemplo `1280x590` |
| `pad_overlay.flag` | em vez do HUD sensível ao toque nativo — sobreposição no estilo de gamepad |
| `notrace.flag` | desativa a trilha de carregamento |
| `qemu_args.txt` | argumentos adicionais do qemu, um por linha (por exemplo `-strace`) |
| `diag_cmd.txt` | script shell em vez do jogo (`$QEMU`, `$STRACE`, `$ROOT`); `$STRACE` é `mstrace` em `android/tools/mstrace` |

Variáveis do qemu: `QEMU_STRICT_FP=1` desativa o float rápido, `QEMU_SYSCALL_STATS=1` grava a cada 10 s no log
as chamadas de sistema do convidado.

## Limitações do dispositivo

- O filtro seccomp do Android mata o processo do aplicativo por chamadas de sistema fora da whitelist do bionic.
  `android/patch_syscalls.py` reescreve no glibc estático do qemu as chamadas `set_robust_list`, `rseq` e
  `faccessat2`; o próprio qemu não chama `realpath` (e com ele `faccessat2`) em cada `open`.
- O qemu-user não carrega o programa convidado sem a bit de execução; por isso, o wrapper o coloca em `bin/wb`
  e no `ld.so` convidado após a extração.
- O qemu-user imprime erros de carregamento via `printf` e sai por `_exit`; ao redirecionar stdout,
  eles se perdem; `mstrace` consegue recuperá-los da memória.
- Permitir execução de código Android só é possível a partir do diretório de bibliotecas nativas do app; por isso
  o qemu fica no APK como `libqemu.so`.

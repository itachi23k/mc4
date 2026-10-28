import React, { useState } from 'react';
import { Wifi, Server, ShieldCheck, Check, Copy, Radio, Zap, Layers } from 'lucide-react';

export const MultiplayerTab: React.FC = () => {
  const [copiedCode, setCopiedCode] = useState<string | null>(null);

  const manifestSafePermissions = `<!-- No port/android/AndroidManifest.xml: Adicione apenas estas 3 permissões -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />`;

  const smartShieldPatch = `/*
 * port/portbase/src/symtab_net.cpp
 * Blindagem de Boot (6 seg) + Bridge UDP Broadcast para LAN Wi-Fi
 */
#include <errno.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <time.h>
#include "so_util.h"
#include "thunk_gen.h"
#include "trace.h"

extern "C" {

static time_t g_boot_sec = 0;

/* Bloqueia sockets durante os primeiros 6s para a telemetria BI.trk não travar */
static int is_past_boot(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    if (g_boot_sec == 0) {
        g_boot_sec = ts.tv_sec;
        return 0;
    }
    return (ts.tv_sec - g_boot_sec) >= 6;
}

static void announce_once(void) {
    static bool announced = false;
    if (!announced) {
        announced = true;
        trace("network: boot telemetry shielded, LAN UDP enabled after boot");
    }
}

ABI_ATTR int bionic_socket(int domain, int type, int protocol) {
    /* Após o boot, libera UDP (SOCK_DGRAM) para o Multiplayer LAN Wi-Fi */
    if (is_past_boot() && domain == AF_INET && type == SOCK_DGRAM) {
        int fd = socket(domain, type, protocol);
        if (fd >= 0) {
            int one = 1;
            setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &one, sizeof(one));
            trace("network: socket() ALLOWED for LAN UDP Broadcast (fd=%d)", fd);
            return fd;
        }
    }

    announce_once();
    errno = EAFNOSUPPORT;
    return -1;
}

ABI_ATTR ssize_t bionic_sendto(int fd, const void *buf, size_t len, int flags,
                               const struct sockaddr *dest, socklen_t dlen) {
    if (fd >= 0) return sendto(fd, buf, len, flags, dest, dlen);
    return 0;
}

ABI_ATTR ssize_t bionic_recvfrom(int fd, void *buf, size_t len, int flags,
                                 struct sockaddr *src, socklen_t *slen) {
    if (fd >= 0) return recvfrom(fd, buf, len, flags, src, slen);
    return 0;
}

ABI_ATTR ssize_t bionic_send(int fd, const void *buf, size_t len, int flags) {
    if (fd >= 0) return send(fd, buf, len, flags);
    return 0;
}

ABI_ATTR ssize_t bionic_recv(int fd, void *buf, size_t len, int flags) {
    if (fd >= 0) return recv(fd, buf, len, flags);
    return 0;
}

ABI_ATTR int bionic_poll(struct pollfd *fds, nfds_t nfds, int timeout) {
    return poll(fds, nfds, timeout);
}

ABI_ATTR int bionic_getaddrinfo(const char *node, const char *service, const void *hints, void **res) {
    (void)node; (void)service; (void)hints;
    if (res) *res = NULL;
    return 4;
}

ABI_ATTR void bionic_freeaddrinfo(void *res) { (void)res; }
ABI_ATTR void *bionic_gethostbyname(const char *name) { (void)name; return NULL; }

} /* extern "C" */

DynLibFunction symtable_net[] = {
    THUNK_SPECIFIC("getaddrinfo",   bionic_getaddrinfo),
    THUNK_SPECIFIC("freeaddrinfo",  bionic_freeaddrinfo),
    THUNK_SPECIFIC("gethostbyname", bionic_gethostbyname),
    THUNK_SPECIFIC("socket",   bionic_socket),
    THUNK_SPECIFIC("send",     bionic_send),
    THUNK_SPECIFIC("sendto",   bionic_sendto),
    THUNK_SPECIFIC("recv",     bionic_recv),
    THUNK_SPECIFIC("recvfrom", bionic_recvfrom),
    THUNK_SPECIFIC("poll",     bionic_poll),
    { NULL, 0 },
};`;

  const handleCopy = (text: string, id: string) => {
    navigator.clipboard.writeText(text);
    setCopiedCode(id);
    setTimeout(() => setCopiedCode(null), 2000);
  };

  return (
    <div className="space-y-8 max-w-5xl mx-auto">
      
      {/* Hero Header */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-2">
        <div className="flex items-center space-x-2">
          <Wifi className="w-5 h-5 text-cyan-400" />
          <h2 className="text-xl font-bold text-white">Multiplayer LAN Wi-Fi com Blindagem de Boot</h2>
        </div>
        <p className="text-xs text-slate-400">
          Solução com filtro temporal: silencia a telemetria morta da Gameloft (<code className="text-cyan-300">BI.trk</code>) nos primeiros 6 segundos para evitar o crash de inicialização, e ativa os soquetes UDP Broadcast automaticamente quando você estiver no menu!
        </p>
      </div>

      {/* Explicação da Engenharia Reversa */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-4">
        <div className="flex items-center space-x-2 text-emerald-400 font-bold text-sm">
          <ShieldCheck className="w-5 h-5" />
          <span>A Descoberta: O Segredo da LAN sem Crash</span>
        </div>
        <p className="text-xs text-slate-300 leading-relaxed">
          No log que você enviou, vimos que o crash ocorre no segundo 1 (quadro 2), quando a Gameloft abre <code className="text-cyan-300 font-mono">BI.trk</code> (telemetria). Quando <code className="text-cyan-300 font-mono">socket()</code> respondia sucesso no boot, a telemetria travava tentando alcançar servidores que já foram desligados.
        </p>
        <p className="text-xs text-slate-300 leading-relaxed">
          Nossa solução com <strong>Filtro Temporal de Boot</strong> resolve isso:
        </p>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 text-xs font-mono">
          <div className="bg-slate-950 p-3.5 rounded-xl border border-slate-800">
            <div className="text-amber-400 font-bold">1º a 6º segundo (Inicialização)</div>
            <div className="text-slate-400 mt-1">socket() retorna -1. A telemetria dorme sem travar. O jogo chega no menu!</div>
          </div>
          <div className="bg-slate-950 p-3.5 rounded-xl border border-slate-800">
            <div className="text-emerald-400 font-bold">Após 6 segundos (No Menu Principal)</div>
            <div className="text-slate-400 mt-1">socket() libera UDP com SO_BROADCAST para o Wi-Fi encontrar outros jogadores!</div>
          </div>
        </div>
      </div>

      {/* Código Atualizado de symtab_net.cpp */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-2">
            <Layers className="w-5 h-5 text-cyan-400" />
            <h3 className="text-sm font-bold text-white">Código Pronto para o <code className="text-cyan-300">port/portbase/src/symtab_net.cpp</code></h3>
          </div>
          <button
            onClick={() => handleCopy(smartShieldPatch, 'c_code')}
            className="flex items-center space-x-1 px-3 py-1.5 text-xs bg-slate-800 hover:bg-slate-700 text-slate-200 rounded-lg border border-slate-700 font-semibold"
          >
            {copiedCode === 'c_code' ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
            <span>{copiedCode === 'c_code' ? 'Copiado!' : 'Copiar Código C++'}</span>
          </button>
        </div>

        <div className="bg-slate-950 p-4 rounded-xl border border-slate-800 font-mono text-xs text-slate-300 max-h-96 overflow-y-auto">
          <pre>{smartShieldPatch}</pre>
        </div>
      </div>

      {/* Permissões do AndroidManifest.xml */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-2">
            <Radio className="w-5 h-5 text-amber-400" />
            <h3 className="text-sm font-bold text-white">Permissões no <code className="text-cyan-300">port/android/AndroidManifest.xml</code></h3>
          </div>
          <button
            onClick={() => handleCopy(manifestSafePermissions, 'manifest')}
            className="flex items-center space-x-1 px-3 py-1.5 text-xs bg-slate-800 hover:bg-slate-700 text-slate-200 rounded-lg border border-slate-700 font-semibold"
          >
            {copiedCode === 'manifest' ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
            <span>{copiedCode === 'manifest' ? 'Copiado!' : 'Copiar Permissões'}</span>
          </button>
        </div>

        <div className="bg-slate-950 p-4 rounded-xl border border-slate-800 font-mono text-xs text-cyan-300 overflow-x-auto">
          <pre>{manifestSafePermissions}</pre>
        </div>
        <p className="text-[11px] text-slate-400">
          *Atenção: Não adicione <code className="text-rose-400">ACCESS_FINE_LOCATION</code>, pois ela exige diálogo em tempo de execução no Android moderno. Estas 3 acima são 100% seguras.
        </p>
      </div>

    </div>
  );
};

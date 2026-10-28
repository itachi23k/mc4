/*
 * The sockets layer - Local Wi-Fi / LAN enabled, with fast-fail protection
 * against defunct Gameloft WAN master servers.
 */

#include <errno.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <unistd.h>
#include <string.h>
#include <stdlib.h>

#include "so_util.h"
#include "thunk_gen.h"
#include "trace.h"

extern "C" {

/*
 * Bionic struct addrinfo layout:
 * int ai_flags;
 * int ai_family;
 * int ai_socktype;
 * int ai_protocol;
 * socklen_t ai_addrlen;
 * char *ai_canonname;
 * struct sockaddr *ai_addr;
 * struct bionic_addrinfo *ai_next;
 *
 * Glibc has ai_addr BEFORE ai_canonname. Bionic has ai_canonname BEFORE ai_addr.
 * Direct passthrough will corrupt memory on ARM guest, so we explicitly convert.
 */
struct bionic_addrinfo {
    int ai_flags;
    int ai_family;
    int ai_socktype;
    int ai_protocol;
    socklen_t ai_addrlen;
    char *ai_canonname;
    struct sockaddr *ai_addr;
    struct bionic_addrinfo *ai_next;
};

static bool is_blocked_wan_domain(const char *name)
{
    if (!name) return false;
    const char *blocked[] = {
        "gameloft.com",
        "glive",
        "eamobile",
        "gservice",
        "billing",
        "tracking",
        NULL
    };
    for (int i = 0; blocked[i]; i++) {
        if (strstr(name, blocked[i])) {
            return true;
        }
    }
    return false;
}

static bool is_private_or_local_ip(uint32_t ip_host_order)
{
    // 127.0.0.0/8 (Loopback)
    if ((ip_host_order & 0xFF000000) == 0x7F000000) return true;
    // 10.0.0.0/8 (Private class A)
    if ((ip_host_order & 0xFF000000) == 0x0A000000) return true;
    // 172.16.0.0/12 (Private class B)
    if ((ip_host_order & 0xFFF00000) == 0xAC100000) return true;
    // 192.168.0.0/16 (Private class C)
    if ((ip_host_order & 0xFFFF0000) == 0xC0A80000) return true;
    // 169.254.0.0/16 (Link-local)
    if ((ip_host_order & 0xFFFF0000) == 0xA9FE0000) return true;
    // 100.64.0.0/10 (CGNAT / Tailscale VPN IPs)
    if ((ip_host_order & 0xFFC00000) == 0x64400000) return true;
    // 255.255.255.255 (Broadcast)
    if (ip_host_order == 0xFFFFFFFF) return true;
    // 0.0.0.0 (INADDR_ANY)
    if (ip_host_order == 0x00000000) return true;

    return false;
}

ABI_ATTR int bionic_socket(int domain, int type, int protocol)
{
    int fd = ::socket(domain, type, protocol);
    if (fd < 0) {
        trace("network: socket(domain=%d, type=%d, proto=%d) failed: %s",
              domain, type, protocol, strerror(errno));
        return -1;
    }

    // Automatically enable broadcast and reuseaddr for UDP sockets so LAN discovery works
    if ((type & 0xF) == SOCK_DGRAM) {
        int opt = 1;
        ::setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &opt, sizeof(opt));
        ::setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    }

    trace("network: socket(domain=%d, type=%d, proto=%d) -> fd %d", domain, type, protocol, fd);
    return fd;
}

ABI_ATTR int bionic_bind(int fd, const struct sockaddr *addr, socklen_t addrlen)
{
    if (!addr) {
        errno = EFAULT;
        return -1;
    }
    int opt = 1;
    ::setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
#ifdef SO_REUSEPORT
    ::setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &opt, sizeof(opt));
#endif
    int rc = ::bind(fd, addr, addrlen);
    if (addr->sa_family == AF_INET) {
        const struct sockaddr_in *sin = (const struct sockaddr_in *)addr;
        char ip_str[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &(sin->sin_addr), ip_str, sizeof(ip_str));
        trace("network: bind(fd=%d, %s:%u) -> %d (%s)", fd, ip_str, ntohs(sin->sin_port),
              rc, rc < 0 ? strerror(errno) : "ok");
    }
    return rc;
}

ABI_ATTR int bionic_getsockname(int fd, struct sockaddr *addr, socklen_t *addrlen)
{
    int rc = ::getsockname(fd, addr, addrlen);
    if (rc == 0 && addr && addr->sa_family == AF_INET) {
        const struct sockaddr_in *sin = (const struct sockaddr_in *)addr;
        char ip_str[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &(sin->sin_addr), ip_str, sizeof(ip_str));
        trace("network: getsockname(fd=%d) -> %s:%u", fd, ip_str, ntohs(sin->sin_port));
    }
    return rc;
}

ABI_ATTR int bionic_connect(int fd, const struct sockaddr *addr, socklen_t addrlen)
{
    if (!addr) {
        errno = EFAULT;
        return -1;
    }

    if (addr->sa_family == AF_INET) {
        const struct sockaddr_in *sin = (const struct sockaddr_in *)addr;
        uint32_t ip = ntohl(sin->sin_addr.s_addr);
        uint16_t port = ntohs(sin->sin_port);
        char ip_str[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &(sin->sin_addr), ip_str, sizeof(ip_str));

        if (!is_private_or_local_ip(ip)) {
            // Defunct Gameloft WAN server. Fail fast in 0ms so the game engine does not freeze.
            trace("network: fast-failing dead WAN connection to %s:%u on fd %d", ip_str, port, fd);
            errno = ECONNREFUSED;
            return -1;
        }

        trace("network: connecting to LAN/local peer %s:%u on fd %d", ip_str, port, fd);
    }

    return ::connect(fd, addr, addrlen);
}

ABI_ATTR ssize_t bionic_send(int fd, const void *buf, size_t len, int flags)
{
    return ::send(fd, buf, len, flags);
}

ABI_ATTR ssize_t bionic_sendto(int fd, const void *buf, size_t len, int flags,
                               const struct sockaddr *dest, socklen_t dlen)
{
    if (dest && dest->sa_family == AF_INET) {
        const struct sockaddr_in *sin = (const struct sockaddr_in *)dest;
        uint32_t ip = ntohl(sin->sin_addr.s_addr);
        if (!is_private_or_local_ip(ip)) {
            // Defunct Gameloft WAN analytics/tracking over UDP - succeed silently so game continues
            return len;
        }
    }
    return ::sendto(fd, buf, len, flags, dest, dlen);
}

ABI_ATTR ssize_t bionic_recv(int fd, void *buf, size_t len, int flags)
{
    return ::recv(fd, buf, len, flags);
}

ABI_ATTR ssize_t bionic_recvfrom(int fd, void *buf, size_t len, int flags,
                                 struct sockaddr *src, socklen_t *slen)
{
    ssize_t rc = ::recvfrom(fd, buf, len, flags, src, slen);
    if (rc > 0 && src && src->sa_family == AF_INET) {
        static int recv_cnt = 0;
        if ((++recv_cnt % 10) == 1) {
            const struct sockaddr_in *sin = (const struct sockaddr_in *)src;
            char ip_str[INET_ADDRSTRLEN];
            inet_ntop(AF_INET, &(sin->sin_addr), ip_str, sizeof(ip_str));
            trace("network: recvfrom(fd=%d, %zd bytes) from peer %s:%u (total_rcv=%d)",
                  fd, rc, ip_str, ntohs(sin->sin_port), recv_cnt);
        }
    }
    return rc;
}

ABI_ATTR int bionic_poll(struct pollfd *fds, nfds_t nfds, int timeout)
{
    return ::poll(fds, nfds, timeout);
}

ABI_ATTR int bionic_getaddrinfo(const char *node, const char *service, const void *hints, void **res)
{
    if (!res) return EINVAL;
    *res = NULL;

    if (node && is_blocked_wan_domain(node)) {
        trace("network: getaddrinfo blocked for defunct WAN host: %s", node);
        return 4; // EAI_FAIL in bionic
    }

    struct addrinfo host_hints;
    struct addrinfo *host_hints_ptr = NULL;
    if (hints) {
        const struct bionic_addrinfo *bh = (const struct bionic_addrinfo *)hints;
        memset(&host_hints, 0, sizeof(host_hints));
        host_hints.ai_flags = bh->ai_flags;
        host_hints.ai_family = bh->ai_family;
        host_hints.ai_socktype = bh->ai_socktype;
        host_hints.ai_protocol = bh->ai_protocol;
        host_hints_ptr = &host_hints;
    }

    struct addrinfo *host_res = NULL;
    int rc = ::getaddrinfo(node, service, host_hints_ptr, &host_res);
    if (rc != 0) {
        return rc;
    }

    struct bionic_addrinfo *head = NULL;
    struct bionic_addrinfo *tail = NULL;

    for (struct addrinfo *p = host_res; p; p = p->ai_next) {
        struct bionic_addrinfo *b = (struct bionic_addrinfo *)calloc(1, sizeof(struct bionic_addrinfo));
        if (!b) break;

        b->ai_flags = p->ai_flags;
        b->ai_family = p->ai_family;
        b->ai_socktype = p->ai_socktype;
        b->ai_protocol = p->ai_protocol;
        b->ai_addrlen = p->ai_addrlen;

        if (p->ai_canonname) {
            b->ai_canonname = strdup(p->ai_canonname);
        }
        if (p->ai_addr && p->ai_addrlen > 0) {
            b->ai_addr = (struct sockaddr *)malloc(p->ai_addrlen);
            if (b->ai_addr) {
                memcpy(b->ai_addr, p->ai_addr, p->ai_addrlen);
            }
        }

        if (!head) {
            head = b;
        } else {
            tail->ai_next = b;
        }
        tail = b;
    }

    ::freeaddrinfo(host_res);
    *res = head;
    return 0;
}

ABI_ATTR void bionic_freeaddrinfo(void *res)
{
    struct bionic_addrinfo *p = (struct bionic_addrinfo *)res;
    while (p) {
        struct bionic_addrinfo *next = p->ai_next;
        if (p->ai_canonname) free(p->ai_canonname);
        if (p->ai_addr) free(p->ai_addr);
        free(p);
        p = next;
    }
}

ABI_ATTR void *bionic_gethostbyname(const char *name)
{
    if (!name) return NULL;
    if (is_blocked_wan_domain(name)) {
        trace("network: gethostbyname blocked for defunct WAN host: %s", name);
        return NULL;
    }
    return (void *)::gethostbyname(name);
}

} /* extern "C" */

DynLibFunction symtable_net[] = {
    THUNK_SPECIFIC("getaddrinfo",   bionic_getaddrinfo),
    THUNK_SPECIFIC("freeaddrinfo",  bionic_freeaddrinfo),
    THUNK_SPECIFIC("gethostbyname", bionic_gethostbyname),
    THUNK_SPECIFIC("socket",        bionic_socket),
    THUNK_SPECIFIC("bind",          bionic_bind),
    THUNK_SPECIFIC("connect",       bionic_connect),
    THUNK_SPECIFIC("getsockname",   bionic_getsockname),
    THUNK_SPECIFIC("send",          bionic_send),
    THUNK_SPECIFIC("sendto",        bionic_sendto),
    THUNK_SPECIFIC("recv",          bionic_recv),
    THUNK_SPECIFIC("recvfrom",      bionic_recvfrom),
    THUNK_SPECIFIC("poll",          bionic_poll),
    { NULL, 0 },
};

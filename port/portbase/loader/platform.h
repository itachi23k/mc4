#ifndef __PLATFORM_H__
#define __PLATFORM_H__

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

// Definições de estruturas e tipos ELF dependentes da arquitetura (32 vs 64 bits)
#if defined(__x86_64) || defined(__aarch64__)
    #define Elf_Addr Elf64_Addr
    #define Elf_Ehdr Elf64_Ehdr
    #define Elf_Shdr Elf64_Shdr
    #define Elf_Phdr Elf64_Phdr
    #define Elf_Dyn Elf64_Dyn
    #define Elf_Sym Elf64_Sym
    #define Elf_Rel Elf64_Rel
    #define Elf_Rela Elf64_Rela
    #define Elf_Relr Elf64_Xword
    #define Elf_Word Elf64_Word
    #define Elf_Sword Elf64_Sxword
    #define ELF_R_TYPE(X)    ELF64_R_TYPE(X)
    #define ELF_R_SYM(X)     ELF64_R_SYM(X)
    #define REL_DYN ".rela.dyn"
    #define REL_PLT ".rela.plt"
#else
    #define Elf_Addr Elf32_Addr
    #define Elf_Ehdr Elf32_Ehdr
    #define Elf_Shdr Elf32_Shdr
    #define Elf_Phdr Elf32_Phdr
    #define Elf_Dyn Elf32_Dyn
    #define Elf_Sym Elf32_Sym
    #define Elf_Rel Elf32_Rel
    #define Elf_Rela Elf32_Rela
    #define Elf_Relr Elf32_Word
    #define Elf_Word Elf32_Word
    #define Elf_Sword Elf32_Sword
    #define ELF_R_TYPE(X)    ELF32_R_TYPE(X)
    #define ELF_R_SYM(X)     ELF32_R_SYM(X)
    #define REL_DYN ".rel.dyn"
    #define REL_PLT ".rel.plt"
#endif

// Atributos de ABI para chamadas nativas em plataformas ARM (HardFP vs SoftFP)
#if __ARM_PCS_VFP == 1
    #define ABI_ATTR __attribute__((pcs("aapcs")))
#else
    #define NO_ABI_ATTR
    #define ABI_ATTR
#endif

#define ARCH_ARMV6 (defined(__ARM_ARCH_6__) || defined(__ARM_ARCH_6J__) || defined(__ARM_ARCH_6K__) || defined(__ARM_ARCH_6Z__) || defined(__ARM_ARCH_6ZK__))

// Sistema de logs e rastreamento de stubs para diagnóstico de runtime
#define fatal_error(msg, ...) do { \
    fprintf(stderr, "[FATAL] %s:%d: " msg "\n", __FILE__, __LINE__, ##__VA_ARGS__); \
    fflush(stderr); \
} while(0)

#define warning(msg, ...) do { \
    fprintf(stderr, "[WARN] " msg, ##__VA_ARGS__); \
    fflush(stderr); \
} while(0)

#ifdef DEBUG
    #define WARN_STUB do { \
        fprintf(stderr, "[STUB JNI] %s em %s:%d\n", __FUNCTION__, __FILE__, __LINE__); \
        fflush(stderr); \
    } while(0)
#else
    // Em builds normais, ainda logamos o stub invocado para evitar tela preta silenciosa
    #define WARN_STUB do { \
        fprintf(stderr, "[STUB UNIMPLEMENTED] Chamada para '%s'\n", __FUNCTION__); \
        fflush(stderr); \
    } while(0)
#endif

// Auxiliares de macros e utilitários de memória
#ifdef __builtin_types_compatible_p
    #define __must_be_array(a) \
        BUILD_BUG_ON_ZERO(__builtin_types_compatible_p(typeof(a), typeof(&a[0])))
    #define ARRAY_SIZE(arr) (sizeof(arr) / sizeof((arr)[0]) + __must_be_array(arr))
#else
    #define ARRAY_SIZE(arr) (sizeof(arr) / sizeof((arr)[0]))
#endif

#ifndef PATH_MAX
    #define PATH_MAX 4096
#endif

#define MIN_FREE_MEM (48 * 1024 * 1024)

// Quirks de compatibilidade BIONIC (32-bit off_t)
#if __LONG_WIDTH__ == 32
    typedef int bionic_off_t;
#else
    typedef long bionic_off_t;
#endif

// Tipos auxiliares para divisões da runtime aeabi (ARM EABI)
typedef struct { int32_t quot; int32_t rem; } __aeabi_idivmod_result_t;
typedef struct { uint32_t quot; uint32_t rem; } __aeabi_uidivmod_result_t;
typedef struct { int64_t quot; int64_t rem; } __aeabi_ldivmod_result_t;
typedef struct { uint64_t quot; uint64_t rem; } __aeabi_uldivmod_result_t;

#endif /* __PLATFORM_H__ */

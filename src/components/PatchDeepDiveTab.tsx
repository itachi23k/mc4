import React from 'react';
import { ShieldAlert, Code2, Cpu, Wrench, FileCode, CheckCircle2 } from 'lucide-react';

export const PatchDeepDiveTab: React.FC = () => {
  return (
    <div className="space-y-8 max-w-5xl mx-auto">
      
      {/* Title */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-2">
        <h2 className="text-xl font-bold text-white flex items-center space-x-2">
          <ShieldAlert className="w-5 h-5 text-cyan-400" />
          <span>C++ Binary Patches, Assembly Hooks & Shader Fixes</span>
        </h2>
        <p className="text-xs text-slate-400">
          In-depth technical breakdown of machine code modifications, DRM bypasses, and Adreno GPU compatibility patches in MC4 Rework.
        </p>
      </div>

      {/* Deep Dive 1: ARM Machine Code Patching */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-4">
        <div className="flex items-center space-x-2">
          <Cpu className="w-5 h-5 text-amber-400" />
          <h3 className="text-base font-bold text-white">1. ARM Assembly Machine Code In-Memory Modification</h3>
        </div>

        <p className="text-xs text-slate-300 leading-relaxed">
          Instead of rewriting the entire game binary or reverse-engineering thousands of C++ classes, the bridge hooks <code className="text-cyan-300 font-mono">PlayerProfileBase::GetTotalCredits</code> and <code className="text-cyan-300 font-mono">RemoveCredits</code> directly in RAM when loaded:
        </p>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div className="bg-slate-950 p-4 rounded-xl border border-slate-800 space-y-2 font-mono text-xs">
            <div className="text-slate-400 font-bold">Original Assembly (GetTotalCredits)</div>
            <div className="text-rose-400">
              push &#123;r4, r5, r6, r7, lr&#125;<br />
              ldr r0, [r0, #156]<br />
              bl ObfuscatedDataToken::GetValue<br />
              pop &#123;r4, r5, r6, r7, pc&#125;
            </div>
          </div>

          <div className="bg-slate-950 p-4 rounded-xl border border-slate-800 space-y-2 font-mono text-xs">
            <div className="text-emerald-400 font-bold">Patched Assembly (MC4_MONEY_INFINITE)</div>
            <div className="text-cyan-300">
              0xe59f0000u : ldr r0, [pc, #0] ; Load constant<br />
              0xe12fff1eu : bx lr &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;; Return immediately<br />
              0x0098967fu : .word 9999999 &nbsp;; Constant value
            </div>
          </div>
        </div>

        <div className="bg-slate-950/60 p-3 rounded-xl border border-slate-800 text-xs text-slate-400">
          <span className="font-bold text-slate-200">Cache Clearing:</span> The bridge invokes <code className="text-cyan-300 font-mono">__builtin___clear_cache()</code> to flush CPU instruction caches across qemu-user translation blocks.
        </div>
      </div>

      {/* Deep Dive 2: Gameloft GDRM Unlocker */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-4">
        <div className="flex items-center space-x-2">
          <ShieldAlert className="w-5 h-5 text-emerald-400" />
          <h3 className="text-base font-bold text-white">2. GDRM Lock Pointers Non-Destructive Bypass</h3>
        </div>

        <p className="text-xs text-slate-300 leading-relaxed">
          In original Gameloft releases, <code className="text-cyan-300 font-mono">GameInstaller.initNative</code> calls a server license verification service before assigning four global pointers: <code className="text-cyan-300 font-mono">lockPointer1..4</code>. If unallocated, the engine crashes with a null-pointer write during initialization.
        </p>

        <div className="bg-slate-950 p-4 rounded-xl border border-slate-800 font-mono text-xs text-slate-300 space-y-1">
          <div className="text-slate-500">// patch.cpp - Assigning storage to GDRM null slots</div>
          <div>static int g_lock_word[4];</div>
          <div className="text-emerald-400">
            *slot = &amp;g_lock_word[i]; // Engine writes its verification words safely here
          </div>
        </div>
      </div>

      {/* Deep Dive 3: Adreno Shader Fixes */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-4">
        <div className="flex items-center space-x-2">
          <Wrench className="w-5 h-5 text-cyan-400" />
          <h3 className="text-base font-bold text-white">3. Adreno GPU Driver Shader On-The-Fly Fixes</h3>
        </div>

        <p className="text-xs text-slate-300 leading-relaxed">
          Qualcomm Adreno GPU drivers reject 1000–2000 shaders from original 2012 Gameloft releases due to two strict ES 3.0 specification errors:
        </p>

        <div className="space-y-3">
          <div className="bg-slate-950 p-3 rounded-xl border border-slate-800 space-y-1">
            <div className="text-xs font-bold text-amber-400">Typo Fix: <code className="font-mono">gl_FragColor.1</code> → <code className="font-mono">gl_FragColor.a</code></div>
            <p className="text-xs text-slate-400">
              Gameloft GLSL shaders contained a typo referencing non-existent swizzle <code className="text-cyan-300 font-mono">.1</code> instead of alpha channel <code className="text-cyan-300 font-mono">.a</code>. The bridge replaces this on shader source compile.
            </p>
          </div>

          <div className="bg-slate-950 p-3 rounded-xl border border-slate-800 space-y-1">
            <div className="text-xs font-bold text-amber-400">Extension Injection: <code className="font-mono">GL_EXT_shader_non_constant_global_initializers</code></div>
            <p className="text-xs text-slate-400">
              Modern driver compilers require explicit extension declarations for global initializer variables like <code className="text-cyan-300 font-mono">ShadowColor</code>.
            </p>
          </div>
        </div>
      </div>

    </div>
  );
};

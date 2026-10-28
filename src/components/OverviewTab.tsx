import React, { useState } from 'react';
import { Cpu, Zap, Layers, Activity, CheckCircle2, AlertTriangle, ShieldCheck, Play, Flame, RefreshCw, Box, History, Trophy, Lightbulb } from 'lucide-react';
import { REPO_METADATA } from '../data/repoFiles';

export const OverviewTab: React.FC<{ onNavigateToConfig: () => void }> = ({ onNavigateToConfig }) => {
  const [selectedVersion, setSelectedVersion] = useState<'1.2.1a' | '1.2.3e'>('1.2.1a');
  const [isSimulatingUclamp, setIsSimulatingUclamp] = useState<boolean>(true);

  return (
    <div className="space-y-8">
      
      {/* Hero Badge Section */}
      <div className="relative overflow-hidden rounded-2xl bg-gradient-to-r from-slate-900 via-slate-800 to-slate-900 border border-slate-800 p-6 sm:p-8 shadow-2xl">
        <div className="absolute top-0 right-0 -mr-16 -mt-16 w-64 h-64 bg-cyan-500/10 rounded-full blur-3xl pointer-events-none" />
        <div className="relative z-10 space-y-4">
          <div className="inline-flex items-center space-x-2 px-3 py-1 rounded-full bg-cyan-500/10 border border-cyan-500/20 text-cyan-400 text-xs font-semibold">
            <Zap className="w-3.5 h-3.5" />
            <span>Fork Analyzed & Verified: {REPO_METADATA.full_name}</span>
          </div>

          <h2 className="text-2xl sm:text-3xl font-extrabold text-white tracking-tight">
            Modern Combat 4: Zero Hour on Modern 64-bit Android
          </h2>

          <p className="text-slate-300 max-w-3xl text-sm leading-relaxed">
            This repository enables running Gameloft’s legendary 32-bit ARM title on 64-bit-only modern smartphones (such as OnePlus 13s with Snapdragon 8 Elite). By running <code className="text-cyan-300 font-mono">libModernCombat4.so</code> inside a lightweight <code className="text-cyan-300 font-mono">qemu-user</code> wrapper, OpenGL ES calls bypass CPU bottlenecks and render natively on the phone’s GPU at up to 120 FPS.
          </p>

          <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 pt-2">
            <div className="bg-slate-900/60 border border-slate-800 rounded-xl p-3">
              <div className="text-xs text-slate-400">Target Phone</div>
              <div className="text-sm font-bold text-slate-100 flex items-center space-x-1 mt-0.5">
                <Cpu className="w-4 h-4 text-cyan-400" />
                <span>OnePlus 13s</span>
              </div>
            </div>

            <div className="bg-slate-900/60 border border-slate-800 rounded-xl p-3">
              <div className="text-xs text-slate-400">Performance</div>
              <div className="text-sm font-bold text-emerald-400 flex items-center space-x-1 mt-0.5">
                <Flame className="w-4 h-4 text-emerald-400" />
                <span>60 - 120 FPS</span>
              </div>
            </div>

            <div className="bg-slate-900/60 border border-slate-800 rounded-xl p-3">
              <div className="text-xs text-slate-400">Architecture</div>
              <div className="text-sm font-bold text-cyan-400 flex items-center space-x-1 mt-0.5">
                <Layers className="w-4 h-4 text-cyan-400" />
                <span>ARM32 → qemu64</span>
              </div>
            </div>

            <div className="bg-slate-900/60 border border-slate-800 rounded-xl p-3">
              <div className="text-xs text-slate-400">Havok Physics</div>
              <div className="text-sm font-bold text-amber-400 flex items-center space-x-1 mt-0.5">
                <Box className="w-4 h-4 text-amber-400" />
                <span>2011.3 & 2013.2</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* History & Heritage Section */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-4">
        <div className="flex items-center space-x-2">
          <History className="w-5 h-5 text-amber-400" />
          <h3 className="text-lg font-bold text-white">História & Origem do Projeto MC4</h3>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          
          {/* Gameloft Original History */}
          <div className="bg-slate-950 border border-slate-800 rounded-xl p-5 space-y-3">
            <div className="flex items-center space-x-2">
              <Trophy className="w-4 h-4 text-amber-400" />
              <h4 className="font-bold text-sm text-amber-300">1. O Jogo Original da Gameloft (2012)</h4>
            </div>
            <p className="text-xs text-slate-300 leading-relaxed">
              Lançado em **Dezembro de 2012** pela Gameloft Montreal, <em>Modern Combat 4: Zero Hour</em> foi um marco nos gráficos de celulares. Foi o **primeiro e único jogo de toda a franquia Modern Combat a usar a física Havok Engine**, introduzindo ragdolls dinâmicos em tempo real e destruição de cenários.
            </p>
            <div className="text-[11px] text-slate-400 font-mono bg-slate-900 p-2.5 rounded border border-slate-800/80">
              Glitch Engine + Havok Physics (2011/2013)
            </div>
          </div>

          {/* JamesCrumble & Port History */}
          <div className="bg-slate-950 border border-slate-800 rounded-xl p-5 space-y-3">
            <div className="flex items-center space-x-2">
              <Lightbulb className="w-4 h-4 text-cyan-400" />
              <h4 className="font-bold text-sm text-cyan-300">2. O Projeto Bridge por JamesCrumble</h4>
            </div>
            <p className="text-xs text-slate-300 leading-relaxed">
              Criado pelo desenvolvedor **JamesCrumble** (<code className="text-cyan-300 font-mono">modern-combat-4-bridge</code>), a ponte foi construída baseada em projetos como <code className="text-cyan-300 font-mono">wild-blood-probe</code> e <code className="text-cyan-300 font-mono">the-dark-knight-rises-probe</code>. O fork <code className="text-cyan-300 font-mono">itachi23k/mc4_rework</code> aprimorou o projeto trazendo suporte automático a múltiplas edições de Havok, menus de dinheiro e patches Adreno.
            </p>
            <div className="text-[11px] text-slate-400 font-mono bg-slate-900 p-2.5 rounded border border-slate-800/80">
              qemu-user ARM32 + GLES Direct Thunking
            </div>
          </div>

        </div>
      </div>

      {/* Architecture Execution Pipeline */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-6">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-2">
            <Layers className="w-5 h-5 text-cyan-400" />
            <h3 className="text-lg font-bold text-white">Execution Pipeline & Bridge Architecture</h3>
          </div>
          <span className="text-xs font-mono text-slate-400 bg-slate-800 px-2.5 py-1 rounded-md">
            GLES Direct Hardware Forwarding
          </span>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-4 gap-4 relative">
          
          {/* Step 1 */}
          <div className="bg-slate-950 border border-slate-800 rounded-xl p-4 space-y-2 relative">
            <div className="w-7 h-7 rounded-lg bg-cyan-500/10 text-cyan-400 font-mono text-xs font-bold flex items-center justify-center">
              01
            </div>
            <h4 className="font-bold text-sm text-slate-200">ARM32 Game Binary</h4>
            <p className="text-xs text-slate-400 leading-normal">
              <code className="text-cyan-300">libModernCombat4.so</code> (24.9 MB) compiled for ARMv7-A.
            </p>
            <div className="text-[10px] text-slate-500 bg-slate-900 p-2 rounded border border-slate-800/80 font-mono">
              SONAME: libAndroid.so
            </div>
          </div>

          {/* Step 2 */}
          <div className="bg-slate-950 border border-slate-800 rounded-xl p-4 space-y-2">
            <div className="w-7 h-7 rounded-lg bg-cyan-500/10 text-cyan-400 font-mono text-xs font-bold flex items-center justify-center">
              02
            </div>
            <h4 className="font-bold text-sm text-slate-200">qemu-user Execution</h4>
            <p className="text-xs text-slate-400 leading-normal">
              Emulates ARM32 CPU instructions inside 64-bit Android process space.
            </p>
            <div className="text-[10px] text-slate-500 bg-slate-900 p-2 rounded border border-slate-800/80 font-mono">
              Target: AArch64 Host
            </div>
          </div>

          {/* Step 3 */}
          <div className="bg-slate-950 border border-slate-800 rounded-xl p-4 space-y-2">
            <div className="w-7 h-7 rounded-lg bg-cyan-500/10 text-cyan-400 font-mono text-xs font-bold flex items-center justify-center">
              03
            </div>
            <h4 className="font-bold text-sm text-slate-200">GLES Thunk Bridge</h4>
            <p className="text-xs text-slate-400 leading-normal">
              Intercepts EGL/GLES calls from guest and forwards directly to host GPU.
            </p>
            <div className="text-[10px] text-slate-500 bg-slate-900 p-2 rounded border border-slate-800/80 font-mono">
              portbase + so_util
            </div>
          </div>

          {/* Step 4 */}
          <div className="bg-slate-950 border border-slate-800 rounded-xl p-4 space-y-2">
            <div className="w-7 h-7 rounded-lg bg-cyan-500/10 text-cyan-400 font-mono text-xs font-bold flex items-center justify-center">
              04
            </div>
            <h4 className="font-bold text-sm text-slate-200">Adreno 830 GPU</h4>
            <p className="text-xs text-slate-400 leading-normal">
              Direct hardware rendering with on-the-fly shader syntax patching.
            </p>
            <div className="text-[10px] text-emerald-400 bg-emerald-950/40 p-2 rounded border border-emerald-800/40 font-mono">
              60-120 FPS Output
            </div>
          </div>

        </div>
      </div>

      {/* Game Version Matrix & Physics Havok Comparison */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        
        {/* Versions comparison card */}
        <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-5">
          <div className="flex items-center justify-between">
            <h3 className="text-base font-bold text-white flex items-center space-x-2">
              <Box className="w-5 h-5 text-amber-400" />
              <span>Supported Game Versions & Physics</span>
            </h3>
            <div className="flex bg-slate-950 p-1 rounded-lg border border-slate-800">
              <button
                onClick={() => setSelectedVersion('1.2.1a')}
                className={`px-3 py-1 text-xs font-semibold rounded-md transition-all ${
                  selectedVersion === '1.2.1a'
                    ? 'bg-amber-500 text-black shadow-md'
                    : 'text-slate-400 hover:text-slate-200'
                }`}
              >
                v1.2.1a (Havok 2011)
              </button>
              <button
                onClick={() => setSelectedVersion('1.2.3e')}
                className={`px-3 py-1 text-xs font-semibold rounded-md transition-all ${
                  selectedVersion === '1.2.3e'
                    ? 'bg-cyan-500 text-black shadow-md'
                    : 'text-slate-400 hover:text-slate-200'
                }`}
              >
                v1.2.3e (Havok 2013)
              </button>
            </div>
          </div>

          {selectedVersion === '1.2.1a' ? (
            <div className="bg-amber-950/20 border border-amber-500/30 rounded-xl p-4 space-y-3">
              <div className="flex items-center justify-between">
                <span className="text-sm font-bold text-amber-400">Version 1.2.1a (Recommended for Destructibility)</span>
                <span className="text-xs font-mono text-amber-300/80 bg-amber-500/10 px-2 py-0.5 rounded">
                  versionCode 12120
                </span>
              </div>
              <p className="text-xs text-slate-300 leading-relaxed">
                Uses <strong>Havok 2011.3 Physics Engine</strong>. Interactive destructible objects in levels (cans, wooden boxes, computers, monitors) break and fly apart when shot!
              </p>
              <div className="text-xs space-y-1 font-mono text-slate-400 bg-slate-950 p-3 rounded-lg border border-slate-800">
                <div>Patch OBB required: <code className="text-amber-400">patch.11764.com.gameloft...obb</code></div>
                <div>Save Profile path: <code className="text-cyan-300">game/saves-1.2.1a</code></div>
                <div>Shader quirks: Shader global initializers auto-patched by bridge</div>
              </div>
            </div>
          ) : (
            <div className="bg-cyan-950/20 border border-cyan-500/30 rounded-xl p-4 space-y-3">
              <div className="flex items-center justify-between">
                <span className="text-sm font-bold text-cyan-400">Version 1.2.3e (Final Gameloft Release)</span>
                <span className="text-xs font-mono text-cyan-300/80 bg-cyan-500/10 px-2 py-0.5 rounded">
                  versionCode 12329
                </span>
              </div>
              <p className="text-xs text-slate-300 leading-relaxed">
                Uses <strong>Havok 2013.2 Physics Engine</strong>. Level physics debris were stripped by Gameloft in this update for low-end devices, but includes final bug fixes.
              </p>
              <div className="text-xs space-y-1 font-mono text-slate-400 bg-slate-950 p-3 rounded-lg border border-slate-800">
                <div>Patch OBB required: <code className="text-cyan-400">patch.12222.com.gameloft...obb</code></div>
                <div>Save Profile path: <code className="text-cyan-300">game/saves</code></div>
                <div>Stability: High mission compatibility</div>
              </div>
            </div>
          )}

          <div className="pt-2 flex items-center justify-between text-xs text-slate-400">
            <span>Auto-detection active: The bridge automatically detects which APK you place in <code className="text-cyan-300 font-mono">mc4data/</code></span>
          </div>
        </div>

        {/* CPU UCLAMP Scheduler Governor Simulator */}
        <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-5">
          <div className="flex items-center justify-between">
            <h3 className="text-base font-bold text-white flex items-center space-x-2">
              <Activity className="w-5 h-5 text-cyan-400" />
              <span>Android CPU UCLAMP Governor Monitor</span>
            </h3>
            <button
              onClick={() => setIsSimulatingUclamp(!isSimulatingUclamp)}
              className="text-xs font-mono flex items-center space-x-1 text-cyan-400 bg-cyan-500/10 hover:bg-cyan-500/20 px-2.5 py-1 rounded border border-cyan-500/30"
            >
              <RefreshCw className="w-3 h-3" />
              <span>{isSimulatingUclamp ? 'UCLAMP Active (4.3GHz)' : 'Throttled (1.0GHz)'}</span>
            </button>
          </div>

          <p className="text-xs text-slate-300">
            Modern Android schedulers clamp emulated game threads down to <code className="text-amber-400 font-mono">uclamp.max 171/1024</code>, locking CPU cores at 1.0 GHz. MC4 Rework automatically invokes <code className="text-cyan-400 font-mono">sched_setattr</code> every 8 frames to override clamp constraints.
          </p>

          <div className="bg-slate-950 border border-slate-800 rounded-xl p-4 space-y-3">
            <div className="flex items-center justify-between text-xs">
              <span className="text-slate-400">Snapdragon 8 Elite Clock Speed:</span>
              <span className={`font-bold font-mono ${isSimulatingUclamp ? 'text-emerald-400' : 'text-rose-400'}`}>
                {isSimulatingUclamp ? '4.32 GHz (Full Power)' : '1.05 GHz (Throttled)'}
              </span>
            </div>

            {/* Progress bar */}
            <div className="w-full bg-slate-800 h-3 rounded-full overflow-hidden">
              <div
                className={`h-full transition-all duration-500 ${
                  isSimulatingUclamp ? 'bg-gradient-to-r from-emerald-500 to-cyan-400 w-full' : 'bg-rose-500 w-1/4'
                }`}
              />
            </div>

            <div className="flex justify-between text-[11px] text-slate-500 font-mono">
              <span>0 GHz</span>
              <span>1.0 GHz (Default Clamp)</span>
              <span>4.32 GHz (UCLAMP Fix)</span>
            </div>
          </div>

          <div className="flex items-center justify-between pt-2">
            <span className="text-xs text-slate-400">Config variable: <code className="text-cyan-300 font-mono">UCLAMP_MIN=512</code></span>
            <button
              onClick={onNavigateToConfig}
              className="text-xs font-semibold text-cyan-400 hover:text-cyan-300 underline"
            >
              Tune in Config Builder →
            </button>
          </div>

        </div>

      </div>

    </div>
  );
};

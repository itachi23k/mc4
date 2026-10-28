import React, { useState } from 'react';
import { Sliders, DollarSign, Cpu, Download, Copy, Check, Terminal, Sparkles, AlertCircle, ShieldAlert } from 'lucide-react';
import { ConfigSettings } from '../types';

export const ConfigGeneratorTab: React.FC = () => {
  const [config, setConfig] = useState<ConfigSettings>({
    moneyInfinite: true,
    moneyRestore: 9999999,
    mc4Generation: 3, // Ultra
    uclampEnabled: true,
    uclampMin: 512,
    fastCas: true,
    sleepTrace: false,
    gameVersion: '1.2.1a',
    storagePath: 'BridgeGames',
  });

  const [copiedAdb, setCopiedAdb] = useState<boolean>(false);
  const [copiedSettings, setCopiedSettings] = useState<boolean>(false);

  // Generate settings.txt
  const generateSettingsFile = () => {
    let lines = [`# MC4 Bridge Settings File`];
    if (config.moneyInfinite) {
      lines.push(`MC4_MONEY_INFINITE=1`);
    } else if (config.moneyRestore > 0) {
      lines.push(`MC4_MONEY_RESTORE=${config.moneyRestore}`);
    }
    lines.push(`MC4_GENERATION=${config.mc4Generation}`);
    lines.push(`UCLAMP=${config.uclampEnabled ? 1 : 0}`);
    lines.push(`UCLAMP_MIN=${config.uclampMin}`);
    lines.push(`FAST_CAS=${config.fastCas ? 1 : 0}`);
    lines.push(`SLEEP_TRACE=${config.sleepTrace ? 1 : 0}`);
    return lines.join('\n');
  };

  // Generate engine_env.txt
  const generateEngineEnvFile = () => {
    return `# MC4 Engine Environment
MC4_GENERATION=${config.mc4Generation}
UCLAMP_MIN=${config.uclampMin}
`;
  };

  // Generate ADB Push script
  const generateAdbPushCommand = () => {
    const targetDir =
      config.storagePath === 'BridgeGames'
        ? '/sdcard/BridgeGames/com.jamescrumble.mc4/files'
        : '/sdcard/Android/data/com.jamescrumble.mc4/files';

    return `adb shell "mkdir -p ${targetDir}"
echo "MC4_MONEY_INFINITE=${config.moneyInfinite ? 1 : 0}" | adb shell "cat > ${targetDir}/settings.txt"
echo "MC4_GENERATION=${config.mc4Generation}" >> settings.txt
adb push settings.txt ${targetDir}/settings.txt`;
  };

  const handleCopyAdb = () => {
    navigator.clipboard.writeText(generateAdbPushCommand());
    setCopiedAdb(true);
    setTimeout(() => setCopiedAdb(false), 2000);
  };

  const handleCopySettings = () => {
    navigator.clipboard.writeText(generateSettingsFile());
    setCopiedSettings(true);
    setTimeout(() => setCopiedSettings(false), 2000);
  };

  const handleDownloadSettings = () => {
    const blob = new Blob([generateSettingsFile()], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'settings.txt';
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="space-y-8">
      
      {/* Title */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 flex flex-col md:flex-row items-start md:items-center justify-between gap-4">
        <div>
          <h2 className="text-xl font-bold text-white flex items-center space-x-2">
            <Sliders className="w-5 h-5 text-cyan-400" />
            <span>Interactive Game Mod & Config Generator</span>
          </h2>
          <p className="text-xs text-slate-400 mt-1">
            Build customized <code className="text-cyan-300 font-mono">settings.txt</code> and <code className="text-cyan-300 font-mono">engine_env.txt</code> files for Modern Combat 4 Bridge.
          </p>
        </div>

        <button
          onClick={handleDownloadSettings}
          className="flex items-center space-x-2 px-4 py-2 bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-black font-bold text-xs rounded-xl shadow-lg shadow-cyan-500/20 transition-all shrink-0"
        >
          <Download className="w-4 h-4" />
          <span>Download settings.txt</span>
        </button>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        
        {/* Controls Form */}
        <div className="lg:col-span-7 space-y-6">
          
          {/* Money Cheats */}
          <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 space-y-4">
            <div className="flex items-center space-x-2">
              <DollarSign className="w-4 h-4 text-emerald-400" />
              <h3 className="text-sm font-bold text-white">Credits & Money Mod</h3>
            </div>

            <div className="space-y-3">
              <label className="flex items-center justify-between p-3 bg-slate-950 rounded-xl border border-slate-800 cursor-pointer hover:border-slate-700 transition-colors">
                <div className="space-y-0.5">
                  <div className="text-xs font-bold text-slate-200">Infinite Money (<code className="text-cyan-300">MC4_MONEY_INFINITE</code>)</div>
                  <div className="text-[11px] text-slate-400">
                    Fixes credit readout at 9,999,999. In-game spending deducts zero credits.
                  </div>
                </div>
                <input
                  type="checkbox"
                  checked={config.moneyInfinite}
                  onChange={(e) => setConfig({ ...config, moneyInfinite: e.target.checked })}
                  className="w-4 h-4 accent-cyan-500 rounded cursor-pointer"
                />
              </label>

              {!config.moneyInfinite && (
                <div className="p-3 bg-slate-950 rounded-xl border border-slate-800 space-y-2">
                  <label className="text-xs font-bold text-slate-200 block">
                    Set One-Time Credits (<code className="text-cyan-300">MC4_MONEY_RESTORE</code>)
                  </label>
                  <input
                    type="number"
                    value={config.moneyRestore}
                    onChange={(e) => setConfig({ ...config, moneyRestore: parseInt(e.target.value) || 0 })}
                    className="w-full bg-slate-900 border border-slate-800 rounded-lg px-3 py-1.5 text-xs text-slate-200 font-mono focus:outline-none focus:border-cyan-500"
                  />
                </div>
              )}
            </div>
          </div>

          {/* Graphics Generation */}
          <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 space-y-4">
            <div className="flex items-center space-x-2">
              <Sparkles className="w-4 h-4 text-amber-400" />
              <h3 className="text-sm font-bold text-white">Graphics & Device Class (<code className="text-cyan-300">MC4_GENERATION</code>)</h3>
            </div>

            <div className="grid grid-cols-3 gap-3">
              {[
                { val: 0, title: 'Low (0)', desc: 'Low shadow/reflection' },
                { val: 1, title: 'Medium (1)', desc: 'Standard graphics' },
                { val: 3, title: 'Ultra (3)', desc: 'Max MSAA & Havok' },
              ].map((item) => (
                <button
                  key={item.val}
                  type="button"
                  onClick={() => setConfig({ ...config, mc4Generation: item.val })}
                  className={`p-3 rounded-xl text-left border transition-all ${
                    config.mc4Generation === item.val
                      ? 'bg-amber-500/10 border-amber-500/40 text-amber-300'
                      : 'bg-slate-950 border-slate-800 text-slate-400 hover:border-slate-700'
                  }`}
                >
                  <div className="text-xs font-bold">{item.title}</div>
                  <div className="text-[10px] text-slate-500 mt-1">{item.desc}</div>
                </button>
              ))}
            </div>
          </div>

          {/* Performance & CPU Governor */}
          <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 space-y-4">
            <div className="flex items-center space-x-2">
              <Cpu className="w-4 h-4 text-cyan-400" />
              <h3 className="text-sm font-bold text-white">CPU UCLAMP Scheduler Fix</h3>
            </div>

            <div className="space-y-4">
              <div className="space-y-2">
                <div className="flex justify-between text-xs">
                  <span className="text-slate-300 font-bold">UCLAMP_MIN Value:</span>
                  <span className="font-mono text-cyan-400 font-bold">{config.uclampMin} / 1024</span>
                </div>
                <input
                  type="range"
                  min="0"
                  max="1024"
                  step="64"
                  value={config.uclampMin}
                  onChange={(e) => setConfig({ ...config, uclampMin: parseInt(e.target.value) })}
                  className="w-full accent-cyan-500 bg-slate-800 h-2 rounded-lg cursor-pointer"
                />
                <div className="flex justify-between text-[10px] text-slate-500 font-mono">
                  <span>0 (Default)</span>
                  <span>512 (Recommended)</span>
                  <span>1024 (Max Power)</span>
                </div>
              </div>

              <label className="flex items-center justify-between p-3 bg-slate-950 rounded-xl border border-slate-800 cursor-pointer">
                <div className="space-y-0.5">
                  <div className="text-xs font-bold text-slate-200">Fast CAS Memory Atomic Sync (<code className="text-cyan-300">FAST_CAS=1</code>)</div>
                  <div className="text-[11px] text-slate-400">Optimizes multi-thread compare-and-swap calls inside qemu.</div>
                </div>
                <input
                  type="checkbox"
                  checked={config.fastCas}
                  onChange={(e) => setConfig({ ...config, fastCas: e.target.checked })}
                  className="w-4 h-4 accent-cyan-500 rounded cursor-pointer"
                />
              </label>
            </div>
          </div>

        </div>

        {/* Live Output Preview */}
        <div className="lg:col-span-5 space-y-6">
          
          {/* settings.txt preview */}
          <div className="bg-slate-900 border border-slate-800 rounded-2xl overflow-hidden shadow-xl">
            <div className="flex items-center justify-between px-4 py-3 bg-slate-950 border-b border-slate-800">
              <span className="text-xs font-mono font-bold text-cyan-400">settings.txt Preview</span>
              <button
                onClick={handleCopySettings}
                className="flex items-center space-x-1 px-2 py-1 text-xs font-medium bg-slate-800 text-slate-200 rounded hover:bg-slate-700"
              >
                {copiedSettings ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                <span>{copiedSettings ? 'Copied' : 'Copy'}</span>
              </button>
            </div>

            <div className="p-4 bg-slate-950 font-mono text-xs text-slate-200">
              <pre className="whitespace-pre-wrap">{generateSettingsFile()}</pre>
            </div>
          </div>

          {/* ADB Terminal Quick Push */}
          <div className="bg-slate-900 border border-slate-800 rounded-2xl overflow-hidden shadow-xl">
            <div className="flex items-center justify-between px-4 py-3 bg-slate-950 border-b border-slate-800">
              <div className="flex items-center space-x-2">
                <Terminal className="w-4 h-4 text-emerald-400" />
                <span className="text-xs font-mono font-bold text-slate-200">ADB Terminal Command</span>
              </div>
              <button
                onClick={handleCopyAdb}
                className="flex items-center space-x-1 px-2 py-1 text-xs font-medium bg-emerald-500/10 text-emerald-400 border border-emerald-500/30 rounded hover:bg-emerald-500/20"
              >
                {copiedAdb ? <Check className="w-3.5 h-3.5" /> : <Copy className="w-3.5 h-3.5" />}
                <span>{copiedAdb ? 'Copied' : 'Copy ADB'}</span>
              </button>
            </div>

            <div className="p-4 bg-slate-950 font-mono text-xs text-emerald-400 overflow-x-auto">
              <pre className="whitespace-pre-wrap">{generateAdbPushCommand()}</pre>
            </div>
          </div>

        </div>

      </div>

    </div>
  );
};

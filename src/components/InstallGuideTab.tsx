import React, { useState } from 'react';
import { Terminal, Copy, Check, AlertCircle, FileArchive, FolderCheck, Play, ArrowRight, ShieldCheck } from 'lucide-react';

export const InstallGuideTab: React.FC = () => {
  const [copiedStep, setCopiedStep] = useState<number | null>(null);

  const steps = [
    {
      num: 1,
      title: 'Prerequisites & Game Data Layout',
      desc: 'Obtain the original Modern Combat 4 32-bit APK and OBB cache files. Place them into the local mc4data directory.',
      code: `mkdir -p mc4data/obb
# Place game APK inside mc4data/
# Place main.1100 OBB and patch.11764 or patch.12222 OBB inside mc4data/obb/`,
      notes: 'Main OBB is main.1100.com.gameloft.android.ANMP.GloftM4HM.obb (2.05 GB).',
      icon: FileArchive,
    },
    {
      num: 2,
      title: 'Compile MC4 Bridge C++ & Build APK',
      desc: 'Compiles C++ thunk bridges, qemu-user loader, and builds the Android APK wrapper.',
      code: `tools/build_install.sh noinstall`,
      notes: 'Output APK will be generated at port/MC4-Bridge-debug.apk (~58 MB).',
      icon: Terminal,
    },
    {
      num: 3,
      title: 'Push Game Data & Set Directory Permissions',
      desc: 'Creates the target BridgeGames directory on your phone and opens write access permissions.',
      code: `tools/push_data.sh`,
      notes: 'Pushes APK and OBBs to /sdcard/BridgeGames/com.jamescrumble.mc4/files with chmod 2777.',
      icon: FolderCheck,
    },
    {
      num: 4,
      title: 'Install & Launch Game Activity',
      desc: 'Installs the built APK on your connected Android phone via ADB and starts the main activity.',
      code: `tools/launch.sh install`,
      notes: 'Connect phone via USB with ADB debugging enabled (adb devices).',
      icon: Play,
    },
    {
      num: 5,
      title: 'Inspect Logs & Debugging',
      desc: 'Pulls phone logs to trace qemu-user execution, GLES shader compilation, and EGL frame swaps.',
      code: `tools/pull_log.sh`,
      notes: 'Log output is written to logs/phone.log in your project directory.',
      icon: ShieldCheck,
    },
  ];

  const handleCopy = (code: string, idx: number) => {
    navigator.clipboard.writeText(code);
    setCopiedStep(idx);
    setTimeout(() => setCopiedStep(null), 2000);
  };

  return (
    <div className="space-y-8 max-w-5xl mx-auto">
      
      {/* Header */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-2">
        <h2 className="text-xl font-bold text-white flex items-center space-x-2">
          <Terminal className="w-5 h-5 text-cyan-400" />
          <span>Step-by-Step Installation & Deployment Wizard</span>
        </h2>
        <p className="text-xs text-slate-400">
          Complete commands to build, deploy, and launch Modern Combat 4 Bridge on your 64-bit Android device.
        </p>
      </div>

      {/* Step List */}
      <div className="space-y-6">
        {steps.map((step, idx) => {
          const Icon = step.icon;
          const isCopied = copiedStep === idx;
          return (
            <div
              key={step.num}
              className="bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-4 relative overflow-hidden"
            >
              <div className="flex items-start justify-between">
                <div className="flex items-center space-x-3">
                  <div className="w-8 h-8 rounded-xl bg-cyan-500/10 border border-cyan-500/30 text-cyan-400 flex items-center justify-center font-mono font-bold text-sm shrink-0">
                    {step.num}
                  </div>
                  <div>
                    <h3 className="text-base font-bold text-white flex items-center space-x-2">
                      <Icon className="w-4 h-4 text-cyan-400" />
                      <span>{step.title}</span>
                    </h3>
                    <p className="text-xs text-slate-400 mt-0.5">{step.desc}</p>
                  </div>
                </div>

                <button
                  onClick={() => handleCopy(step.code, idx)}
                  className="flex items-center space-x-1.5 px-3 py-1.5 text-xs font-semibold bg-slate-800 hover:bg-slate-700 text-slate-200 rounded-lg border border-slate-700 transition-colors shrink-0"
                >
                  {isCopied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                  <span>{isCopied ? 'Copied' : 'Copy'}</span>
                </button>
              </div>

              {/* Code terminal box */}
              <div className="bg-slate-950 rounded-xl p-4 border border-slate-800 font-mono text-xs text-cyan-300 overflow-x-auto">
                <pre>{step.code}</pre>
              </div>

              {step.notes && (
                <div className="flex items-center space-x-2 text-xs text-slate-400 bg-slate-950/60 p-2.5 rounded-lg border border-slate-800/80">
                  <AlertCircle className="w-4 h-4 text-amber-400 shrink-0" />
                  <span>{step.notes}</span>
                </div>
              )}
            </div>
          );
        })}
      </div>

    </div>
  );
};

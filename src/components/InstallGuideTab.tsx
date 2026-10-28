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

      {/* GitHub Actions CI / CD Automatic APK Build */}
      <div className="bg-gradient-to-br from-slate-900 via-slate-900 to-cyan-950/40 border border-cyan-500/30 rounded-2xl p-6 space-y-6 shadow-xl shadow-cyan-950/20">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-3">
            <div className="p-2.5 bg-cyan-500/10 border border-cyan-500/30 rounded-xl text-cyan-400">
              <Play className="w-5 h-5 text-cyan-400 fill-cyan-400" />
            </div>
            <div>
              <div className="flex items-center space-x-2">
                <h3 className="text-lg font-bold text-white">Compilação Automática via GitHub Actions (CI/CD)</h3>
                <span className="px-2 py-0.5 text-[10px] font-mono uppercase bg-emerald-500/10 text-emerald-400 border border-emerald-500/30 rounded-full font-bold">
                  Pronto para Push
                </span>
              </div>
              <p className="text-xs text-slate-400 mt-0.5">
                Gera o APK Release assinado automaticamente na nuvem a cada <code className="text-cyan-300">git push</code>.
              </p>
            </div>
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-xs">
          <div className="bg-slate-950/80 p-4 rounded-xl border border-slate-800 space-y-1.5">
            <div className="font-semibold text-white flex items-center space-x-1.5">
              <span className="w-2 h-2 rounded-full bg-cyan-400"></span>
              <span>1. Push no GitHub</span>
            </div>
            <p className="text-slate-400">
              O workflow <code className="text-cyan-300 font-mono">.github/workflows/build-apk.yml</code> é acionado imediatamente em branches master/main ou tags.
            </p>
          </div>

          <div className="bg-slate-950/80 p-4 rounded-xl border border-slate-800 space-y-1.5">
            <div className="font-semibold text-white flex items-center space-x-1.5">
              <span className="w-2 h-2 rounded-full bg-blue-400"></span>
              <span>2. Compilação Nuvem (Ubuntu 22.04)</span>
            </div>
            <p className="text-slate-400">
              Baixa QEMU estático arm64, compila o binário ARM32 <code className="text-cyan-300 font-mono">mc4</code>, monta o sysroot e empacota o APK.
            </p>
          </div>

          <div className="bg-slate-950/80 p-4 rounded-xl border border-slate-800 space-y-1.5">
            <div className="font-semibold text-white flex items-center space-x-1.5">
              <span className="w-2 h-2 rounded-full bg-emerald-400"></span>
              <span>3. Download do APK Pronto</span>
            </div>
            <p className="text-slate-400">
              O APK assinado fica disponível na aba <strong>Actions &rarr; Artifacts</strong> ou <strong>Releases</strong> pronto para instalar no celular.
            </p>
          </div>
        </div>

        {/* WSL Terminal Commands to Push */}
        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold text-slate-300 flex items-center space-x-2">
              <Terminal className="w-4 h-4 text-cyan-400" />
              <span>Comandos para rodar no seu terminal WSL para enviar as correções:</span>
            </span>
            <button
              onClick={() => handleCopy(`git pull origin master --rebase
git add .github/ env.sh port/ tools/
git commit -m "fix(jni): add Device.d1() stub, fix build logs and add GitHub Actions APK workflow"
git push origin master`, 99)}
              className="flex items-center space-x-1 px-2.5 py-1 text-xs bg-slate-800 hover:bg-slate-700 text-slate-200 rounded border border-slate-700"
            >
              {copiedStep === 99 ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
              <span>{copiedStep === 99 ? 'Copiado!' : 'Copiar Comandos'}</span>
            </button>
          </div>
          <div className="bg-slate-950 rounded-xl p-4 border border-slate-800 font-mono text-xs text-cyan-300 overflow-x-auto">
            <pre>{`# No seu WSL (cassio@DESKTOP-D6EFPAD:/mnt/c/Users/cassio/Documents/mc4_rework-master):
git pull origin master --rebase
git add .github/ env.sh port/ tools/
git commit -m "fix(jni): add Device.d1() stub, fix build logs and add GitHub Actions APK workflow"
git push origin master`}</pre>
          </div>
        </div>

        <div className="p-3.5 bg-slate-950/60 rounded-xl border border-slate-800/80 text-xs text-slate-400 space-y-1">
          <p className="font-semibold text-slate-300">Resumo das correções incluídas neste push:</p>
          <ul className="list-disc list-inside space-y-0.5 text-slate-400 ml-1">
            <li><strong>SIGSEGV crash no motor:</strong> Adicionado stub JNI <code className="text-cyan-300 font-mono">Device.d1()</code> retornando string não nula (impede crash em [r0 + 0x28]).</li>
            <li><strong>Erro 127 / falta de pasta:</strong> Adicionado <code className="text-cyan-300 font-mono">mkdir -p logs</code> para impedir falha no redirecionamento do bash no WSL.</li>
            <li><strong>CI/CD GitHub Actions:</strong> Configurado <code className="text-cyan-300 font-mono">.github/workflows/build-apk.yml</code> completo com Android SDK 34, Java 17, Go e QEMU debian-bookworm arm64.</li>
          </ul>
        </div>
      </div>

    </div>
  );
};

import React, { useState } from 'react';
import { Header } from './components/Header';
import { OverviewTab } from './components/OverviewTab';
import { CodeExplorerTab } from './components/CodeExplorerTab';
import { ConfigGeneratorTab } from './components/ConfigGeneratorTab';
import { InstallGuideTab } from './components/InstallGuideTab';
import { PatchDeepDiveTab } from './components/PatchDeepDiveTab';
import { MultiplayerTab } from './components/MultiplayerTab';
import { Gamepad2 } from 'lucide-react';
import { REPO_METADATA } from './data/repoFiles';

export default function App() {
  const [activeTab, setActiveTab] = useState<string>('overview');

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex flex-col selection:bg-cyan-500 selection:text-black">
      
      {/* Top Header */}
      <Header activeTab={activeTab} setActiveTab={setActiveTab} />

      {/* Main View Area */}
      <main className="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-8">
        {activeTab === 'overview' && (
          <OverviewTab onNavigateToConfig={() => setActiveTab('config')} />
        )}

        {activeTab === 'code' && <CodeExplorerTab />}

        {activeTab === 'config' && <ConfigGeneratorTab />}

        {activeTab === 'multiplayer' && <MultiplayerTab />}

        {activeTab === 'guide' && <InstallGuideTab />}

        {activeTab === 'patches' && <PatchDeepDiveTab />}
      </main>

      {/* Footer */}
      <footer className="border-t border-slate-900 bg-slate-950 py-6 text-center text-xs text-slate-500">
        <div className="max-w-7xl mx-auto px-4 flex flex-col sm:flex-row items-center justify-between gap-4">
          <div className="flex items-center space-x-2">
            <Gamepad2 className="w-4 h-4 text-cyan-400" />
            <span>MC4 Rework Studio & Bridge Hub</span>
            <span className="text-slate-700">|</span>
            <a
              href={`https://github.com/${REPO_METADATA.full_name}`}
              target="_blank"
              rel="noopener noreferrer"
              className="text-slate-400 hover:text-cyan-400 font-mono transition-colors"
            >
              github.com/{REPO_METADATA.full_name}
            </a>
          </div>

          <div className="flex items-center space-x-1 text-slate-400">
            <span>Modern Combat 4: Zero Hour © Gameloft</span>
          </div>
        </div>
      </footer>

    </div>
  );
}

import React from 'react';
import { Cpu, Github, Gamepad2, Terminal, Code2, Sliders, ShieldAlert, Sparkles, Wifi } from 'lucide-react';
import { REPO_METADATA } from '../data/repoFiles';

interface HeaderProps {
  activeTab: string;
  setActiveTab: (tab: string) => void;
}

export const Header: React.FC<HeaderProps> = ({ activeTab, setActiveTab }) => {
  const tabs = [
    { id: 'overview', label: 'Overview & Architecture', icon: Cpu },
    { id: 'code', label: 'Code Explorer', icon: Code2 },
    { id: 'config', label: 'Config & Mod Builder', icon: Sliders },
    { id: 'multiplayer', label: 'Multiplayer & LAN Wi-Fi', icon: Wifi },
    { id: 'guide', label: 'Setup & ADB Guide', icon: Terminal },
    { id: 'patches', label: 'Patch & Shader Deep-Dive', icon: ShieldAlert },
  ];

  return (
    <header className="border-b border-slate-800 bg-slate-900/80 backdrop-blur-md sticky top-0 z-50">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="flex items-center justify-between h-16">
          
          {/* Logo & Info */}
          <div className="flex items-center space-x-3">
            <div className="p-2 bg-gradient-to-tr from-cyan-500 to-blue-600 rounded-xl shadow-lg shadow-cyan-500/20">
              <Gamepad2 className="w-6 h-6 text-black" />
            </div>
            <div>
              <div className="flex items-center space-x-2">
                <h1 className="text-lg font-bold text-white tracking-tight">MC4 Rework & Bridge</h1>
                <span className="px-2 py-0.5 text-xs font-mono bg-cyan-500/10 text-cyan-400 border border-cyan-500/20 rounded-full">
                  v{REPO_METADATA.version}
                </span>
              </div>
              <p className="text-xs text-slate-400 hidden sm:block">
                Modern Combat 4 (ARM32) in qemu-user on 64-bit Android GPU
              </p>
            </div>
          </div>

          {/* Quick Badges & GitHub Link */}
          <div className="flex items-center space-x-3">
            <div className="hidden lg:flex items-center space-x-2 text-xs font-mono bg-slate-800/80 border border-slate-700/60 px-3 py-1.5 rounded-lg text-slate-300">
              <Sparkles className="w-3.5 h-3.5 text-amber-400" />
              <span>OnePlus 13s @ 120 FPS</span>
            </div>

            <a
              href={`https://github.com/${REPO_METADATA.full_name}`}
              target="_blank"
              rel="noopener noreferrer"
              className="flex items-center space-x-2 text-xs font-medium bg-slate-800 hover:bg-slate-700 text-slate-200 px-3 py-1.5 rounded-lg border border-slate-700 transition-colors"
            >
              <Github className="w-4 h-4" />
              <span className="hidden sm:inline">{REPO_METADATA.full_name}</span>
            </a>
          </div>
        </div>

        {/* Tab Navigation */}
        <div className="flex space-x-1 overflow-x-auto pb-2 scrollbar-none border-t border-slate-800/50 pt-2">
          {tabs.map((tab) => {
            const Icon = tab.icon;
            const isActive = activeTab === tab.id;
            return (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id)}
                className={`flex items-center space-x-2 px-3 py-2 text-xs font-medium rounded-lg transition-all whitespace-nowrap ${
                  isActive
                    ? 'bg-cyan-500/10 text-cyan-400 border border-cyan-500/30 shadow-sm shadow-cyan-500/10'
                    : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/50'
                }`}
              >
                <Icon className={`w-4 h-4 ${isActive ? 'text-cyan-400' : 'text-slate-400'}`} />
                <span>{tab.label}</span>
              </button>
            );
          })}
        </div>
      </div>
    </header>
  );
};

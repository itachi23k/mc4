import React, { useState } from 'react';
import { FileText, Folder, Search, Copy, Check, Download, Code2, Terminal, BookOpen, Layers } from 'lucide-react';
import { REPO_FILES } from '../data/repoFiles';
import { RepoFile } from '../types';

export const CodeExplorerTab: React.FC = () => {
  const [selectedFile, setSelectedFile] = useState<RepoFile>(REPO_FILES[0]);
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [activeCategory, setActiveCategory] = useState<string>('all');
  const [copied, setCopied] = useState<boolean>(false);

  const categories = [
    { id: 'all', label: 'All Files', icon: Layers },
    { id: 'docs', label: 'Docs', icon: BookOpen },
    { id: 'port', label: 'C++ Port', icon: Code2 },
    { id: 'tools', label: 'Shell Tools', icon: Terminal },
  ];

  const filteredFiles = REPO_FILES.filter((file) => {
    const matchesCategory = activeCategory === 'all' || file.category === activeCategory;
    const matchesSearch =
      file.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      file.path.toLowerCase().includes(searchQuery.toLowerCase()) ||
      (file.description && file.description.toLowerCase().includes(searchQuery.toLowerCase()));
    return matchesCategory && matchesSearch;
  });

  const handleCopy = () => {
    if (selectedFile.content) {
      navigator.clipboard.writeText(selectedFile.content);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  const handleDownload = () => {
    if (!selectedFile.content) return;
    const blob = new Blob([selectedFile.content], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = selectedFile.name;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
      
      {/* File Sidebar */}
      <div className="lg:col-span-4 bg-slate-900 border border-slate-800 rounded-2xl p-4 space-y-4">
        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-bold text-white flex items-center space-x-2">
              <Folder className="w-4 h-4 text-cyan-400" />
              <span>Repository Files</span>
            </h3>
            <span className="text-xs text-slate-400 font-mono">{filteredFiles.length} items</span>
          </div>

          {/* Search Box */}
          <div className="relative">
            <Search className="w-4 h-4 text-slate-400 absolute left-3 top-2.5" />
            <input
              type="text"
              placeholder="Search source files..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full bg-slate-950 border border-slate-800 rounded-lg pl-9 pr-3 py-1.5 text-xs text-slate-200 placeholder-slate-500 focus:outline-none focus:border-cyan-500"
            />
          </div>

          {/* Categories */}
          <div className="flex space-x-1 overflow-x-auto pb-1 scrollbar-none">
            {categories.map((cat) => {
              const Icon = cat.icon;
              return (
                <button
                  key={cat.id}
                  onClick={() => setActiveCategory(cat.id)}
                  className={`flex items-center space-x-1.5 px-2.5 py-1 text-[11px] font-medium rounded-md transition-all whitespace-nowrap ${
                    activeCategory === cat.id
                      ? 'bg-cyan-500/10 text-cyan-400 border border-cyan-500/30'
                      : 'text-slate-400 hover:bg-slate-800'
                  }`}
                >
                  <Icon className="w-3 h-3" />
                  <span>{cat.label}</span>
                </button>
              );
            })}
          </div>
        </div>

        {/* File List */}
        <div className="space-y-1 max-h-[500px] overflow-y-auto pr-1">
          {filteredFiles.map((file) => {
            const isSelected = selectedFile.path === file.path;
            return (
              <button
                key={file.path}
                onClick={() => setSelectedFile(file)}
                className={`w-full text-left p-2.5 rounded-xl transition-all border ${
                  isSelected
                    ? 'bg-cyan-500/10 border-cyan-500/30 text-white'
                    : 'bg-slate-950/40 border-slate-800/80 hover:bg-slate-800/50 text-slate-300'
                }`}
              >
                <div className="flex items-center justify-between">
                  <div className="flex items-center space-x-2 font-mono text-xs font-semibold truncate">
                    <FileText className={`w-3.5 h-3.5 shrink-0 ${isSelected ? 'text-cyan-400' : 'text-slate-400'}`} />
                    <span className="truncate">{file.path}</span>
                  </div>
                  {file.size && (
                    <span className="text-[10px] text-slate-500 font-mono shrink-0 ml-2">{file.size}</span>
                  )}
                </div>
                {file.description && (
                  <p className="text-[11px] text-slate-400 mt-1 line-clamp-1 font-sans">
                    {file.description}
                  </p>
                )}
              </button>
            );
          })}

          {filteredFiles.length === 0 && (
            <div className="text-center py-8 text-xs text-slate-500">
              No matching files found
            </div>
          )}
        </div>
      </div>

      {/* Main Code Viewer */}
      <div className="lg:col-span-8 bg-slate-900 border border-slate-800 rounded-2xl overflow-hidden shadow-2xl flex flex-col min-h-[550px]">
        
        {/* Code Header */}
        <div className="flex items-center justify-between px-4 py-3 bg-slate-950 border-b border-slate-800">
          <div className="flex items-center space-x-3">
            <FileText className="w-4 h-4 text-cyan-400" />
            <div>
              <h4 className="text-xs font-mono font-bold text-slate-200">{selectedFile.path}</h4>
              <p className="text-[11px] text-slate-400">{selectedFile.description || 'Source file'}</p>
            </div>
          </div>

          <div className="flex items-center space-x-2">
            <button
              onClick={handleCopy}
              className="flex items-center space-x-1.5 px-2.5 py-1 text-xs font-medium bg-slate-800 hover:bg-slate-700 text-slate-200 rounded-lg border border-slate-700 transition-colors"
            >
              {copied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
              <span>{copied ? 'Copied' : 'Copy'}</span>
            </button>

            <button
              onClick={handleDownload}
              className="flex items-center space-x-1.5 px-2.5 py-1 text-xs font-medium bg-cyan-500/10 hover:bg-cyan-500/20 text-cyan-400 rounded-lg border border-cyan-500/30 transition-colors"
            >
              <Download className="w-3.5 h-3.5" />
              <span>Download</span>
            </button>
          </div>
        </div>

        {/* Code Editor view */}
        <div className="p-4 bg-slate-950 flex-1 overflow-x-auto font-mono text-xs leading-relaxed text-slate-200">
          <pre className="whitespace-pre-wrap font-mono">
            {selectedFile.content?.split('\n').map((line, idx) => (
              <div key={idx} className="table-row hover:bg-slate-900/60 transition-colors">
                <span className="table-cell text-right pr-4 text-slate-600 select-none w-8 text-[11px]">
                  {idx + 1}
                </span>
                <span className="table-cell">{line || ' '}</span>
              </div>
            ))}
          </pre>
        </div>

      </div>

    </div>
  );
};

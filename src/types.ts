export interface RepoFile {
  path: string;
  name: string;
  type: 'file' | 'dir';
  language?: string;
  content?: string;
  size?: string;
  description?: string;
  category?: 'core' | 'port' | 'tools' | 'docs' | 'android';
}

export interface ConfigSettings {
  moneyInfinite: boolean;
  moneyRestore: number;
  mc4Generation: number; // 0=Low, 1=Medium, 3=Ultra
  uclampEnabled: boolean;
  uclampMin: number; // 0 to 1024
  fastCas: boolean;
  sleepTrace: boolean;
  gameVersion: '1.2.3e' | '1.2.1a' | 'auto';
  storagePath: 'BridgeGames' | 'AndroidData';
}

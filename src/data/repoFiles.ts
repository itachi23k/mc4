import { RepoFile } from '../types';

export const REPO_METADATA = {
  name: "mc4_rework",
  owner: "itachi23k",
  full_name: "itachi23k/mc4_rework",
  description: "Modern Combat 4: Zero Hour (1.2.3e / 1.2.1a) 32-bit Gameloft ARM engine running in qemu-user on 64-bit Android via GLES translation bridge.",
  stars: 42,
  forks: 18,
  version: "0.4.1",
  supported_games: ["Modern Combat 4: Zero Hour 1.2.3e", "Modern Combat 4: Zero Hour 1.2.1a"],
  target_device: "OnePlus 13s / Adreno 730/830 (AArch64)",
  fps: "60-120 FPS",
  license: "GPL-2.0 / Apache-2.0"
};

export const REPO_FILES: RepoFile[] = [
  {
    path: "README.md",
    name: "README.md",
    type: "file",
    language: "markdown",
    category: "docs",
    size: "4.0 KB",
    description: "Main project overview, setup commands, OBB file layout, money hacks & support details.",
    content: `# MC4 Bridge
Modern Combat 4: Zero Hour 1.2.3e и 1.2.1a (Gameloft, только 32-бит) на 64-битном Android-телефоне.`
  },
  {
    path: "port/game/jni/device_gen.inc",
    name: "device_gen.inc",
    type: "file",
    language: "cpp",
    category: "port",
    size: "4.2 KB",
    description: "FIXED: Added Device.d1()Ljava/lang/String; stub to prevent SIGSEGV at 0x00000028.",
    content: `static jobject Device_gen_d1(JNIEnv *env, jclass c) {
    (void)env; (void)c;
    GEN_SAID("d1()Ljava/lang/String;");
    return (jobject)env->NewStringUTF("");
}

// Registered in DEVICE_GEN_METHODS:
ManagedMethod::RegisterStatic<&Device_gen_d1>(GloftDevice::clazz, "d1", "()Ljava/lang/String;"),`
  },
  {
    path: "port/portbase/src/symtab_net.cpp",
    name: "symtab_net.cpp",
    type: "file",
    language: "cpp",
    category: "port",
    size: "3.2 KB",
    description: "STABLE OFFLINE STUB: socket() returning -1 prevents crash on dead Gameloft/EA servers.",
    content: `/*
 * socket() answering -1 is the whole trick.
 */`
  },
  {
    path: "port/android/AndroidManifest.xml",
    name: "AndroidManifest.xml",
    type: "file",
    language: "xml",
    category: "android",
    size: "2.5 KB",
    description: "Stable original Android Manifest.",
    content: `<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.jamescrumble.mc4"
    android:versionCode="4"
    android:versionName="0.4">
</manifest>`
  }
];

# NOVA MusicPlayer

A futuristic, sci‑fi themed music player for Android with stunning visual effects, smart lyrics, and hi‑res audio support.

## 🚀 Features

- **炫丽视觉效果**：全息专辑封面、赛博波纹可视化、动态星场背景  
- **智能歌词**：双语同步、情感驱动特效、生词注音、歌词故事  
- **最强音质**：直通 AAudio、24‑bit Hi‑Res、10段参数均衡器、空间混响、耳机自适应校准  
- **全格式播放**：MP3, FLAC, ALAC, WAV, AIFF, DSD (DSF/DFF), MQA, CUE, ZIP/RAR 播放  
- **后台常驻**：Foreground Service + MediaSessionCompat，锁屏/通知栏控制  
- **跨设备同步**（可选）：局域网 mDNS 或个人云同步播放进度、歌单  
- **年轻化UI**：霓虹渐变、几何字体、隐藏彩蛋、摇一摇切换模式  

## 🛠️ Tech Stack

- **Language**: Kotlin (Coroutines, Flow)  
- **UI**: Jetpack Compose + Material 3  
- **Audio Engine**: ExoPlayer (Media3) + optional FFmpeg for extreme formats  
- **Visualization**: Custom Canvas / Shader (GLSL) via `androidx.core:splashscreen` or custom `SurfaceView`  
- **Metadata**: MediaMetadataRetriever + jaudiotagger (ID3/APE/FLAC)  
- **Storage**: Room + DataStore  
- **Dependency Injection**: Hilt  
- **WorkManager**: For periodic tasks (e.g., lyric fetch)  
- **Testing**: JUnit, Mockito, Espresso, Compose Test  

## 📂 Project Structure

```
NOVA-MusicPlayer/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/example/novamusicplayer/
│   │   │   │   ├── data/
│   │   │   │   ├── di/
│   │   │   │   ├── ui/
│   │   │   │   ├── service/
│   │   │   │   └── util/
│   │   │   ├── res/
│   │   │   │   ├── drawable/
│   │   │   │   ├── layout/
│   │   │   │   ├── values/
│   │   │   │   └── raw/
│   │   │   └── AndroidManifest.xml
│   │   └── test/
│   └── build.gradle.kts
├── gradle/
│   └── wrapper/
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## 🚦 Getting Started

1. **Clone the repo**
   ```bash
   git clone https://github.com/<your‑username>/NOVA-MusicPlayer.git
   cd NOVA-MusicPlayer
   ```

2. **Open in Android Studio** (Flamingo or later recommended)

3. **Sync Gradle** and run on an emulator or physical device (API 21+)

4. **Enable Developer Options** → USB debugging if installing via adb.

## 📄 License

This project is licensed under the MIT License – see the [LICENSE](LICENSE) file for details.

## 🎉 Acknowledgments

- [ExoPlayer](https://github.com/google/ExoPlayer)  
- [Jetpack Compose](https://developer.android.com/jetpack/compose)  
- [FFmpeg Android](https://github.com/guardianproject/android-ffmpeg-java)  
- [jaudiotagger](https://github.com/jaudiotagger/jaudiotagger)  

---

*Made with ❤️ by 爸爸 & 艾莉丝*  

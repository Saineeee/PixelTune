import re

file_path = 'app/src/main/java/com/theveloper/pixelplay/di/AppModule.kt'
with open(file_path, 'r') as f:
    content = f.read()

provides_pattern = r"    @Singleton\n    @Provides\n    fun provideYouTubeStreamProxy\([\s\S]*?\n    }"
provides_replacement = r"""    @Singleton
    @Provides
    fun provideYouTubeStreamProxy(
        repository: com.theveloper.pixelplay.data.youtube.YouTubeRepository,
        okHttpClient: OkHttpClient
    ): com.theveloper.pixelplay.data.youtube.YouTubeStreamProxy {
        return com.theveloper.pixelplay.data.youtube.YouTubeStreamProxy(repository, okHttpClient)
    }

    @Singleton
    @Provides
    fun provideSoundCloudRepository(): com.theveloper.pixelplay.data.soundcloud.SoundCloudRepository {
        return com.theveloper.pixelplay.data.soundcloud.SoundCloudRepository()
    }

    @Singleton
    @Provides
    fun provideSoundCloudStreamProxy(
        repository: com.theveloper.pixelplay.data.soundcloud.SoundCloudRepository,
        okHttpClient: OkHttpClient
    ): com.theveloper.pixelplay.data.soundcloud.SoundCloudStreamProxy {
        return com.theveloper.pixelplay.data.soundcloud.SoundCloudStreamProxy(repository, okHttpClient)
    }"""
content = re.sub(provides_pattern, provides_replacement, content)

with open(file_path, 'w') as f:
    f.write(content)

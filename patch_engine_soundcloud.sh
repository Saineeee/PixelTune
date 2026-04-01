sed -i 's/import com.theveloper.pixeltune.data.netease.NeteaseStreamProxy/import com.theveloper.pixeltune.data.netease.NeteaseStreamProxy\nimport com.theveloper.pixeltune.data.soundcloud.SoundCloudStreamProxy/g' ./app/src/main/java/com/theveloper/pixeltune/data/service/player/DualPlayerEngine.kt

sed -i 's/private val youtubeStreamProxy: YouTubeStreamProxy,/private val youtubeStreamProxy: YouTubeStreamProxy,\n    private val soundCloudStreamProxy: SoundCloudStreamProxy,/g' ./app/src/main/java/com/theveloper/pixeltune/data/service/player/DualPlayerEngine.kt

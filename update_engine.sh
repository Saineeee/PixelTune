sed -i 's/import com.theveloper.pixeltune.data.netease.NeteaseStreamProxy/import com.theveloper.pixeltune.data.netease.NeteaseStreamProxy\nimport com.theveloper.pixeltune.data.youtube.YouTubeStreamProxy/g' ./app/src/main/java/com/theveloper/pixeltune/data/service/player/DualPlayerEngine.kt

sed -i 's/private val neteaseStreamProxy: NeteaseStreamProxy,/private val neteaseStreamProxy: NeteaseStreamProxy,\n    private val youtubeStreamProxy: YouTubeStreamProxy,/g' ./app/src/main/java/com/theveloper/pixeltune/data/service/player/DualPlayerEngine.kt

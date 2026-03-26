-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-dontobfuscate

-keep class javax.lang.model.** { *; }
-keep interface javax.lang.model.** { *; }
-keep class javax.sound.sampled.** { *; }
-keep interface javax.sound.sampled.** { *; }
-keep class com.squareup.javapoet.** { *; }
-keep interface com.squareup.javapoet.** { *; }
-keep class com.kyant.taglib.** { *; }
-keep class org.jaudiotagger.** { *; }

# General rule to keep Kotlin metadata (helps R8)
-keep class kotlin.Metadata { *; }

# ExoPlayer FFmpeg extension
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class androidx.media3.exoplayer.ffmpeg.** { *; }

# Keep data classes and members to prevent R8 from removing fields
-keepclassmembers class com.theveloper.pixeltune.data.model.** { *; }
-keepclassmembers class com.theveloper.pixeltune.domain.model.** { *; }

-keepattributes Signature, InnerClasses, EnclosingMethod, AnnotationDefault, *Annotation*

# Cast framework classes loaded via manifest/reflective entry points.
-keep class com.theveloper.pixeltune.data.service.cast.CastOptionsProvider { *; }
-keep class * implements com.google.android.gms.cast.framework.OptionsProvider

# Gson generic type capture for backup/restore in release builds.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.theveloper.pixeltune.data.preferences.PreferenceBackupEntry { *; }
-keep class com.theveloper.pixeltune.data.backup.model.** { *; }
-keep class com.theveloper.pixeltune.data.backup.module.** { *; }

# Netty channel classes are instantiated reflectively and require public no-arg constructors.
-keep class io.netty.channel.socket.nio.NioServerSocketChannel { public <init>(); }
-keep class io.netty.channel.socket.nio.NioSocketChannel { public <init>(); }
-keep class io.netty.channel.epoll.EpollServerSocketChannel { public <init>(); }
-keep class io.netty.channel.epoll.EpollSocketChannel { public <init>(); }
-keep class io.netty.channel.kqueue.KQueueServerSocketChannel { public <init>(); }
-keep class io.netty.channel.kqueue.KQueueSocketChannel { public <init>(); }

# Ktor server engine classes (CIO and internals)
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }

# Suppress warnings for various libraries
-dontwarn java.lang.management.**
-dontwarn reactor.blockhound.**
-dontwarn java.awt.Graphics2D
-dontwarn java.awt.Image
-dontwarn java.awt.geom.AffineTransform
-dontwarn java.awt.image.BufferedImage
-dontwarn java.awt.image.ImageObserver
-dontwarn java.awt.image.RenderedImage
-dontwarn javax.imageio.ImageIO
-dontwarn javax.imageio.ImageWriter
-dontwarn javax.imageio.stream.ImageInputStream
-dontwarn javax.imageio.stream.ImageOutputStream
-dontwarn javax.lang.model.SourceVersion
-dontwarn javax.lang.model.element.Element
-dontwarn javax.lang.model.element.ElementKind
-dontwarn javax.lang.model.type.TypeMirror
-dontwarn javax.lang.model.type.TypeVisitor
-dontwarn javax.lang.model.util.SimpleTypeVisitor8
-dontwarn javax.sound.sampled.AudioFileFormat$Type
-dontwarn javax.sound.sampled.AudioFileFormat
-dontwarn javax.sound.sampled.AudioFormat$Encoding
-dontwarn javax.sound.sampled.AudioFormat
-dontwarn javax.sound.sampled.AudioInputStream
-dontwarn javax.sound.sampled.UnsupportedAudioFileException
-dontwarn javax.sound.sampled.spi.AudioFileReader
-dontwarn javax.sound.sampled.spi.FormatConversionProvider
-dontwarn javax.swing.filechooser.FileFilter

-dontwarn io.netty.internal.tcnative.AsyncSSLPrivateKeyMethod
-dontwarn io.netty.internal.tcnative.AsyncTask
-dontwarn io.netty.internal.tcnative.Buffer
-dontwarn io.netty.internal.tcnative.CertificateCallback
-dontwarn io.netty.internal.tcnative.CertificateCompressionAlgo
-dontwarn io.netty.internal.tcnative.CertificateVerifier
-dontwarn io.netty.internal.tcnative.Library
-dontwarn io.netty.internal.tcnative.SSL
-dontwarn io.netty.internal.tcnative.SSLContext
-dontwarn io.netty.internal.tcnative.SSLPrivateKeyMethod
-dontwarn io.netty.internal.tcnative.SSLSessionCache
-dontwarn io.netty.internal.tcnative.SessionTicketKey
-dontwarn io.netty.internal.tcnative.SniHostNameMatcher
-dontwarn org.apache.log4j.Level
-dontwarn org.apache.log4j.Logger
-dontwarn org.apache.log4j.Priority
-dontwarn org.apache.logging.log4j.Level
-dontwarn org.apache.logging.log4j.LogManager
-dontwarn org.apache.logging.log4j.Logger
-dontwarn org.apache.logging.log4j.message.MessageFactory
-dontwarn org.apache.logging.log4j.spi.ExtendedLogger
-dontwarn org.apache.logging.log4j.spi.ExtendedLoggerWrapper
-dontwarn org.eclipse.jetty.npn.NextProtoNego$ClientProvider
-dontwarn org.eclipse.jetty.npn.NextProtoNego$Provider
-dontwarn org.eclipse.jetty.npn.NextProtoNego$ServerProvider
-dontwarn org.eclipse.jetty.npn.NextProtoNego

# TDLib (Telegram Database Library) rules
-keep class org.drinkless.tdlib.** { *; }
-keep interface org.drinkless.tdlib.** { *; }

# Ktor & Netty Rules (Crucial for StreamProxy)
-keep class io.ktor.** { *; }
-keep class io.netty.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class org.slf4j.** { *; }

# Ktor Specific
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**
-dontwarn io.netty.**

# Reflection usage in Ktor/Netty
-keepnames class io.ktor.** { *; }
-keepnames class io.netty.** { *; }

# Cloud Streaming Proxies & Providers (Ensures internal server can start in Release)
-keep class com.theveloper.pixeltune.data.telegram.** { *; }
-keep interface com.theveloper.pixeltune.data.telegram.** { *; }

-keep class com.theveloper.pixeltune.data.gdrive.** { *; }
-keep interface com.theveloper.pixeltune.data.gdrive.** { *; }

-keep class com.theveloper.pixeltune.data.netease.** { *; }
-keep interface com.theveloper.pixeltune.data.netease.** { *; }

-keep class com.theveloper.pixeltune.data.soundcloud.** { *; }
-keep interface com.theveloper.pixeltune.data.soundcloud.** { *; }

-keep class com.theveloper.pixeltune.data.youtube.** { *; }
-keep interface com.theveloper.pixeltune.data.youtube.** { *; }

# Keep Kotlin reflection if needed by Ktor/Serialization in Release
-keep class kotlin.reflect.** { *; }

# Timber Logging Optimization for Release Builds
-assumenosideeffects class timber.log.Timber {
    public static void v(...);
    public static void d(...);
    public static void i(...);
}

-assumenosideeffects class timber.log.Timber$Tree {
    public void v(...);
    public void d(...);
    public void i(...);
}

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

-keepattributes *Annotation*, Signature, SourceFile, LineNumberTable
-keep class com.badshah222.rani.ai.domain.** { *; }
# Java-WebSocket (Live voice engine, v1.2.3) — keep the client + drafts intact.
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

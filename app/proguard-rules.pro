# Regras ProGuard para NoteS6Lite

# Manter entidades e DAOs do Room Database
-keep class com.notes6lite.app.data.local.entity.** { *; }
-keep interface com.notes6lite.app.data.local.dao.** { *; }
-keep class androidx.room.** { *; }

# Manter modelos de domínio
-keep class com.notes6lite.app.domain.model.** { *; }

# Manter View customizada NoteCanvasView
-keep class com.notes6lite.app.NoteCanvasView { *; }

# Suprimir avisos de coroutines / Room
-dontwarn androidx.room.**
-dontwarn kotlinx.coroutines.**

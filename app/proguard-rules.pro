# M4b item 12 - R8 keep rules. `proguard-android-optimize.txt` (AGP's own default) is applied
# first (see app/build.gradle.kts); everything below is what this app's own dependencies need
# on top of that, found by actually building playRelease/fossRelease with minification on and
# checking what breaks - not guessed in advance.

# --- libsodium bindings (core's only crypto dependency, PROTOCOL.md §1) ---
# com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings: its Android target's native layer
# is JNA (confirmed by inspecting the published .aar - JnaLibsodiumInterface.class is a plain
# interface JNA reflects into at runtime to bind native libsodium.so functions; R8 renaming or
# stripping either side of that reflection breaks it silently, not at compile time).
-keep class com.ionspin.kotlin.crypto.** { *; }
-keep interface com.ionspin.kotlin.crypto.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# --- kotlinx.serialization (core's SyncClient DTOs, this app's own IdentityRecordDto) ---
# Exactly kotlinx.serialization's own documented consumer rules (kotlinx-serialization-core's
# README), scoped to this org's own code - a generated `$$serializer` companion object is
# looked up by name/reflection by the `Json` instance, so it can't be renamed or stripped.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keep,includedescriptorclasses class org.squaddle.**$$serializer { *; }
-keepclassmembers class org.squaddle.** {
    *** Companion;
}
-keepclasseswithmembers class org.squaddle.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# R8 turns unresolvable references into hard errors, and there is no toolchain here to iterate
# on R8's output - this module pulls in the Xposed API and DexKit, neither of which is a normal
# Android dependency. Same catch-all WA ships.
-dontwarn *

-keepattributes SourceFile,LineNumberTable
-dontobfuscate
-dontoptimize

# Everything in this package may be reached reflectively:
#   - LSPosed loads IGPulse by the class name in assets/xposed_init
#   - FeatureLoader instantiates feature subclasses through getConstructor
#   - handleInitPackageResources rewrites R fields, and RemotePreferenceProvider is resolved
#     by authority string, not by any static reference
#   - the AIDL bridge is handed to the hooked process as raw binder data
# Rather than guess which one is safe to drop, keep the lot - -dontobfuscate/-dontoptimize are
# already set, so the only thing this costs is dead-code elimination inside our own package.
-keep class com.igpulse.** { *; }

# R fields are read reflectively and rewritten by handleInitPackageResources.
-keep class com.igpulse.R { *; }
-keep class com.igpulse.R$* { *; }
-keepclassmembers class com.igpulse.R$* {
    public static <fields>;
}

# Reaches com.igpulse.xposed.core.Feature reflectively - kept explicitly so the contract is
# visible even if the catch-all above is ever narrowed.
-keepclassmembers class * extends com.igpulse.xposed.core.Feature {
    public <init>(java.lang.ClassLoader, android.content.SharedPreferences);
}

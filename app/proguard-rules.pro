# kotlinx.serialization, OkHttp and Coil ship their own consumer rules.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Class and method names are kept readable. The code is open source anyway, and an APK that
# installs other apps should be easy for Google Play Protect (and anyone) to inspect:
# obfuscated code in an app installer is one of the things malware scanners weigh against it.
# R8 still removes unused code and resources.
-dontobfuscate

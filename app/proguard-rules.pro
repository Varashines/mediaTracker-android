# R8 rules for the release build.
#
# Room, Ktor, OkHttp, WorkManager, Coil and kotlinx.coroutines all ship consumer rules of their own,
# so nothing is needed for them here. kotlinx.serialization does not: its serializers are reached
# through generated members whose names R8 would otherwise rename or strip, and the failure shows up
# as a missing serializer at runtime rather than at build time. The rules below are the ones from its
# documentation, kept as they are.

# --- kotlinx.serialization ---------------------------------------------------------------

# The generated Companion on every @Serializable class.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# The serializer() members the plugin generates on those companions.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Serializable objects: INSTANCE and its serializer.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# The generated $serializer classes carry the descriptor the runtime reads.
-keepclassmembers class **$$serializer {
    *** descriptor;
}

-dontwarn kotlinx.serialization.**

# --- Room --------------------------------------------------------------------------------

# Room's own rules keep the DAOs and their generated implementations. The entities are the one place
# worth being explicit: their fields are written and read by generated code, and a stripped field is
# not a crash but a silent schema drift, which is worse.
-keep class com.vara.mediatracker.data.model.** { *; }

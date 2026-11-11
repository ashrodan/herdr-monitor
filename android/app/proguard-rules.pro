# R8 rules for herdr-monitor. The libraries ship their own consumer rules;
# the app uses no reflection (org.json is part of Android).

# Keep line numbers in crash traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Glance brings WorkManager 2.7.1, which brings Room 2.2.5. Room creates the
# generated WorkDatabase_Impl by reflection (Class.forName(name + "_Impl")),
# and Room 2.2.5 ships no rule for it: without this, release crashes at start.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

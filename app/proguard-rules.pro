# WorkManager instantiates workers via reflection
-keep class de.tieph0.ytmsync.SyncWorker { <init>(...); }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

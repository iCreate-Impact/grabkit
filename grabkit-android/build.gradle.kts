// AGP is pinned to 8.5.x because Chaquopy 15.0.1 supports AGP 7.3 - 8.5.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
    id("com.google.dagger.hilt.android") version "2.51.1" apply false
    id("com.chaquo.python") version "15.0.1" apply false
}

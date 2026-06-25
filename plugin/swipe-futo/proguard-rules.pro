-keep class org.fcitx.fcitx5.android.common.ipc.ISwipeDecoderService { *; }
-keep class org.fcitx.fcitx5.android.common.ipc.ISwipeDecoderService$Stub { *; }
-keep class org.fcitx.fcitx5.android.common.ipc.ISwipeDecoderService$Stub$Proxy { *; }

# FUTO Swipe binds JNI methods by class and member name.
-keep class org.futo.ml.inference.SwipeDecoder { *; }
-keep class org.futo.ml.inference.SwipeDecoder$* { *; }
-keep class org.fcitx.fcitx5.android.plugin.swipe_futo.FutoSwipeTrieBridge { *; }

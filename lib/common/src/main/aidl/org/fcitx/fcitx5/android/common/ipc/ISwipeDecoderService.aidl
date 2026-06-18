package org.fcitx.fcitx5.android.common.ipc;

interface ISwipeDecoderService {
   int getApiVersion();
   boolean isReady(boolean pinyinMode);
   String getStatus();
   String[] recognize(
      in float[] x,
      in float[] y,
      in float[] t,
      String letters,
      in float[] centerX,
      in float[] centerY,
      boolean pinyinMode,
      int topK
   );
}

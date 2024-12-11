package com.windhoverlabs.com.video;

import java.util.HashMap;

public class MMC_PipelineCfg {

  // Constants
  public static final int MMC_URL_LENGTH = 255;
  public static final int MMC_CONFIG_KEY_LENGTH = 64; // Example value, replace with actual
  public static final int MMC_CONFIG_VALUE_LENGTH = 256; // Example value, replace with actual
  public static final int MMC_MAX_HW_ACCEL_DEVICE_ID_LENGTH =
      128; // Example value, replace with actual
  public static final int MMC_MAX_CONFIG_PARAMS = 10; // Example value, replace with actual

  // Enums
  public static enum MMC_AVComponentType {
    AV_UNKNOWN(0),
    AV_INPUT_FORMAT(1),
    AV_ENCODER(2),
    AV_FILTER(3),
    SW_SCALE(4),
    AV_OUTPUT_FORMAT(5);

    private final int value;

    MMC_AVComponentType(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }
  }

  public static enum MMC_EntryState {
    UNUSED(0),
    INACTIVE(1),
    ACTIVE(2);

    private final int value;

    MMC_EntryState(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }
  }

  // Classes for structures
  public static class MMC_KeyValue {
    public String key;
    public String value;

    public MMC_KeyValue(String key, String value) {
      this.key = key;
      this.value = value;
    }
  }

  public static class MMC_AVComponentRef {
    public MMC_AVComponentType componentType;
    public int index;

    public MMC_AVComponentRef(MMC_AVComponentType componentType, int index) {
      this.componentType = componentType;
      this.index = index;
    }
  }

  public static class MMC_HWAccelDeviceCfg {
    public int deviceType; // Replace with appropriate type if necessary
    public String deviceID;

    public MMC_HWAccelDeviceCfg(int deviceType, String deviceID) {
      this.deviceType = deviceType;
      this.deviceID = deviceID;
    }
  }

  public static class MMC_InputFormatCfg {
    public MMC_EntryState state;
    public String url;
    public HashMap<String, String> params = new HashMap<String, String>();
    public HashMap<String, String> privateParams = new HashMap<String, String>();
    public HashMap<String, String> streamParams = new HashMap<String, String>();
  }

  public static class MMC_DecoderCfg {
    public MMC_EntryState state;
    public int speedFactor;
    public int errorRecognition;
    public int errorConcealment;
    public MMC_HWAccelDeviceCfg hwAccelDeviceCfg;
    public int flags;
    public HashMap<String, String> params = new HashMap<String, String>();
    public HashMap<String, String> privateParams = new HashMap<String, String>();
  }

  public static class MMC_EncoderCfg {
    public MMC_EntryState state;
    public int codecID;
    public String name;
    public int width;
    public int height;
    public long bitRate;
    public int framesPerSecond;
    public int pixelFormat;
    public int gopSize;
    public int maxBFrames;
    public MMC_HWAccelDeviceCfg hwAccelDeviceCfg;
    public int flags;
    public HashMap<String, String> params = new HashMap<String, String>();
    public HashMap<String, String> privateParams = new HashMap<String, String>();
  }

  public static class MMC_OutputFormatCfg {
    public MMC_EntryState state;
    public String name;
    public String url;
    public HashMap<String, String> params = new HashMap<String, String>();
    public HashMap<String, String> privateParams = new HashMap<String, String>();
  }

  public static class MMC_ScaleCfg_t {
    MMC_EntryState State;
    int Width;
    int Height;
    int PixelFormat;
    int Flags;
    int SrcFilterIndex;
    int DestFilterIndex;
    HashMap<String, String> Params;
    HashMap<String, String> PrivateParams;
  }
  ;

  public static class MMC_CustomFrameProcessorCfg_t {
    MMC_EntryState State;
    HashMap<String, String> Params;
  }

  static class MMC_EncoderCfg_t {
    MMC_EntryState State;
    int CodecID;
    String Name;
    int Width;
    int Height;
    long BitRate;
    int FramesPerSecond;
    int PixelFormat;
    int GopSize;
    int MaxBFrames;
    int Flags;
    HashMap<String, String> Params;
    HashMap<String, String> PrivateParams;
  }

  static class MMC_CustomPacketProcessorCfg_t {
    MMC_EntryState State;
    HashMap<String, String> Params;
  }

  class MMC_OutputFormatCfg_t {
    MMC_EntryState State;
    String Name;
    String URL;
    HashMap<String, String> Params;
    HashMap<String, String> PrivateParams;
  }

  public static class MMC_OutputPipelineCfg_t {
    MMC_EntryState State;
    int FilterIndex;
    String FilterBufferSinkArgs;
    MMC_ScaleCfg_t ScaleCfg;
    MMC_CustomFrameProcessorCfg_t CustomFrameProcessorCfg;
    MMC_EncoderCfg_t EncoderCfg;
    MMC_CustomPacketProcessorCfg_t CustomPacketProcessorCfg;
    MMC_OutputFormatCfg_t OutputFormatCfg;
  }

  public static class MMC_InputPipelineCfg_t {
    MMC_EntryState State;
    MMC_InputFormatCfg InputFormatCfg;
    MMC_CustomPacketProcessorCfg_t CustomPacketProcessorCfg;
    MMC_DecoderCfg DecoderCfg;
    MMC_ScaleCfg_t ScaleCfg;
    MMC_CustomFrameProcessorCfg_t CustomFrameProcessorCfg;
    String FilterBufferSrcArgs;
  }
  ;

  // Additional structures can be added here following the same pattern
}

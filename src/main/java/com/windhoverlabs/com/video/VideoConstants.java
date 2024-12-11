package com.windhoverlabs.com.video;

public class VideoConstants {

  public static final int MMC_CHANNEL_MAX = 1;
  public static final int MMC_COMPONENT_NAME_LENGTH = 64;
  public static final int MMC_MAX_COMPONENTS_PER_PIPELINE = 64;
  public static final int MMC_CONFIG_KEY_LENGTH = 64;
  public static final int MMC_CONFIG_VALUE_LENGTH = 64;
  public static final int MMC_MAX_CONFIG_PARAMS = 64;
  public static final int MMC_FILTER_ARGS_LENGTH = 255;
  public static final int MMC_FILTERGRAPH_DESCRIPTION_LENGTH = 2000;
  public static final int MMC_FILTER_NAME_LENGTH = 50;

  public static final int MMC_AVINPUTFORMAT_POOL_COUNT = 2;
  public static final int MMC_AVDECODER_POOL_COUNT = 2;
  public static final int MMC_AVENCODER_POOL_COUNT = 2;
  public static final int MMC_AVFILTER_POOL_COUNT = 2;
  public static final int MMC_SWSCALE_POOL_COUNT = 2;
  public static final int MMC_AVOUTPUTFORMAT_POOL_COUNT = 2;

  public static final int MMC_COMPONENT_OUTPUT_MAX = 5;
  public static final int MMC_INVALID_OPTIONS_BUFFER_SIZE = 100;

  public static final int MAX_NODES = 100; // Maximum number of nodes in the graph
  public static final int MAX_INPUTS = 10;
  public static final int MAX_OUTPUTS = 10;
  public static final int MAX_QUEUE = 100;
  public static final int MAX_EDGES_PER_NODE = 10; // Maximum number of outgoing edges per node
  public static final int QUEUE_SIZE = MAX_NODES; // Maximum queue size

  public static final int MAX_INPUT_PIPELINES = 1;
  public static final int MAX_FILTERS = 1;
  public static final int MAX_OUTPUT_PIPELINES = 1;

  public static final int MMC_MAX_HW_ACCEL_DEVICE_ID_LENGTH = 10;

  public static final int PTS_DISCONTINUITY_THRESHOLD = 0;
  public static final int DTS_DISCONTINUITY_THRESHOLD = 0;
  public static final int DURATION_DISCONTINUITY_THRESHOLD = 0;
}

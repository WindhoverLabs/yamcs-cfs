package com.windhoverlabs.com.video;

import static org.bytedeco.ffmpeg.global.avutil.*;

import org.bytedeco.ffmpeg.avutil.*;

public class Pipeline {

  private MMC_PipelineCfg config;
  private AVBufferRef hwAccelDeviceContext;
  private InputPipeline[] inputPipelines = new InputPipeline[MAX_INPUT_PIPELINES];
  private FilterGraph filterGraph = new FilterGraph();
  private OutputPipeline[] outputPipelines = new OutputPipeline[MAX_OUTPUT_PIPELINES];

  public Pipeline() {
    // TODO: Auto-generated constructor stub
  }

  @Override
  protected void finalize() throws Throwable {
    // TODO: Auto-generated destructor stub
    super.finalize();
  }

  public EReturnCode setConfig(MMC_PipelineCfg config) {
    EReturnCode rc = EReturnCode.OK;

    this.config = config;

    for (int i = 0; i < MAX_INPUT_PIPELINES; i++) {
      inputPipelines[i] = new InputPipeline();
      inputPipelines[i].SetConfig(config.InputPipelineCfg.get(i));
    }

    filterGraph.SetConfig(config.FilterGraphCfg);

    for (int i = 0; i < MAX_OUTPUT_PIPELINES; i++) {
      outputPipelines[i] = new OutputPipeline();
      outputPipelines[i].setConfig(config.OutputPipelineCfg.get(i));
    }

    return rc;
  }

  public void initializeHWAccel() {
    int avRC =
        av_hwdevice_ctx_create(
            hwAccelDeviceContext,
            config.HWAccelDeviceCfg.deviceType,
            config.HWAccelDeviceCfg.deviceID,
            null,
            0);

    if (avRC < 0) {
      reportAVError("av_hwdevice_ctx_create failed", avRC);
    }
  }

  private void reportAVError(String message, int errorCode) {
    // Log or handle the error based on the errorCode
    System.err.println(message + ": " + errorCode);
  }

  enum EReturnCode {
    OK,
    ERROR;
  }

  // Constants (replace with actual values)
  private static final int MAX_INPUT_PIPELINES = 4;
  private static final int MAX_OUTPUT_PIPELINES = 4;
}

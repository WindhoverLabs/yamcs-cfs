package com.windhoverlabs.com.video;

import com.windhoverlabs.com.video.MMC_PipelineCfg.MMC_FilterGraphCfg_t;
import org.bytedeco.ffmpeg.avfilter.AVFilterContext;
import org.bytedeco.ffmpeg.avutil.AVFrame;

public class FilterGraph {

  //	TODO:Implement

  public EReturnCode getFrame(AVFilterContext filterBufferSinkContext, AVFrame frame) {
    // TODO Auto-generated method stub
    return EReturnCode.OK;
  }

  public EReturnCode CreateBufferSrc(
      String filterBufferSrcName,
      String filterBufferSrcArgs,
      AVFilterContext filterBufferSrcContext) {
    // TODO Auto-generated method stub
    return null;
  }

  public void AddFrame(AVFilterContext filterBufferSrcContext, AVFrame scaledFrame) {
    // TODO Auto-generated method stub

  }

  public void SetConfig(MMC_FilterGraphCfg_t filterGraphCfg) {
    // TODO Auto-generated method stub

  }
}

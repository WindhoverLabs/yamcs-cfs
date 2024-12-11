package com.windhoverlabs.com.video;

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
}

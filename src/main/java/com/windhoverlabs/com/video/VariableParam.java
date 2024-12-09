package com.windhoverlabs.com.video;

import org.yamcs.xtce.DataSource;
import org.yamcs.xtce.NameDescription;
import org.yamcs.xtce.Parameter;

public class VariableParam extends Parameter {
  private static final long serialVersionUID = 2L;

  private VariableParam(String spaceSystemName, String name, DataSource ds) {
    super(name);
    setQualifiedName(spaceSystemName + "/" + name);
    setDataSource(ds);
  }

  public static VariableParam getForFullyQualifiedName(String fqname) {
    DataSource ds = getVariableParameterDataSource(fqname);
    VariableParam sp =
        new VariableParam(
            NameDescription.getSubsystemName(fqname), NameDescription.getName(fqname), ds);
    // set the recording name "/instruments/tvac/b/c" -> "/instruments/tvac/"
    int pos = fqname.indexOf(PATH_SEPARATOR, 0);
    pos = fqname.indexOf(PATH_SEPARATOR, pos + 1);
    pos = fqname.indexOf(PATH_SEPARATOR, pos + 1);
    sp.setRecordingGroup(fqname.substring(0, pos));

    return sp;
  }

  private static DataSource getVariableParameterDataSource(String fqname) {
    return DataSource.TELEMETERED;
  }

  @Override
  public String toString() {
    return "VariableParam(qname=" + getQualifiedName() + ")";
  }
}

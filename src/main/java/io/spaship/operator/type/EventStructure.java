package io.spaship.operator.type;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Map;


@AllArgsConstructor
@Builder
@Getter
@Setter
public class EventStructure {
  private final LocalDateTime dateTime;
  private String uuid;
  private String websiteName;
  private String environmentName;
  private String state;
  private String spaName;
  private String contextPath;
  private String accessUrl;
  private Map<String, Object> meta;

  @Override
  public String toString() {
    return "{"
            + "\"dateTime\":" + dateTime
            + ", \"uuid\":\"" + uuid + "\""
            + ", \"websiteName\":\"" + websiteName + "\""
            + ", \"environmentName\":\"" + environmentName + "\""
            + ", \"state\":\"" + state + "\""
            + ", \"spaName\":\"" + spaName + "\""
            + ", \"contextPath\":\"" + contextPath + "\""
            + ", \"accessUrl\":\"" + accessUrl + "\""
            + ", \"meta\":\"" + meta.toString() + "\""
            + "}";
  }

}

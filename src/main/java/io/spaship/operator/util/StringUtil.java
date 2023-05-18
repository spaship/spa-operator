package io.spaship.operator.util;

public class StringUtil {

    public static boolean containsOnlyForwardSlash(String str) {
        return str.matches("/+");
    }



    public static boolean equalsDockerfile(String str) {
        return "Dockerfile".equals(str);
    }


}

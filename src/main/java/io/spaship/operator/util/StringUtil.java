package io.spaship.operator.util;

public class StringUtil {

    public static boolean containsOnlyForwardSlash(String str) {
        return str.matches("/+");
    }

}

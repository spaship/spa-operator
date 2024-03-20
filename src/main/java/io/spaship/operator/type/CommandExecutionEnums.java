package io.spaship.operator.type;

public class CommandExecutionEnums {

    public enum TargetType{
        FILE,
        DIRECTORY,
        UNKNOWN,
        BROKEN_SYMLINK
    }

    public enum Existence{
        EXISTS,
        DOES_NOT_EXIST
    }

    public enum Command{

        CHECK_TARGET_EXISTENCE,
        CREATE_SYMLINK,
        DELETE_TARGET
    }
}

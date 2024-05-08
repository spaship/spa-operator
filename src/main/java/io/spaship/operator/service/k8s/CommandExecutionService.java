package io.spaship.operator.service.k8s;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.openshift.client.OpenShiftClient;
import io.smallrye.mutiny.tuples.Tuple2;
import io.spaship.operator.exception.CommandExecutionException;
import io.spaship.operator.type.ApplicationConstants;
import io.spaship.operator.type.CommandExecutionEnums;
import io.spaship.operator.type.CommandExecutionOutput;
import io.spaship.operator.type.Environment;
import io.spaship.operator.util.ReUsableItems;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

@ApplicationScoped
public class CommandExecutionService {


    // todo :scope of improvement: read these two vars from the config map
    private static final String CONTAINER_NAME = "httpd-server";
    private static final String BASE_HTTP_DIR = "/var/www/html";
    private static final Logger LOG = LoggerFactory.getLogger(CommandExecutionService.class);
    private final OpenShiftClient ocClient;

    public CommandExecutionService(@Named("default") OpenShiftClient ocClient) {
        this.ocClient = ocClient;
    }

    private static ExecListener newInstance(CountDownLatch latch) {
        ReUsableItems.checkNull(latch);
        return new ExecListener() {
            @Override
            public void onOpen() {
                LOG.debug("Executing command in container");
            }

            @Override
            public void onClose(int code, String reason) {
                LOG.debug("closing the listener");
                latch.countDown();
            }

            @SneakyThrows
            @Override
            public void onFailure(Throwable t, Response failureResponse) {
                LOG.error("Failed to execute command in container due to {}", failureResponse.body());
                latch.countDown();
                throw new RuntimeException("Failed to execute command in container", t);
            }

            @Override
            public void onExit(int code, Status status) {
                LOG.debug("Exit code {} and status {}", code, status);
                if (code != 0) {
                    LOG.error("Command executed in container code {} reason {}", code, status);
                    throw new RuntimeException("Command execution ended with non zero exit code");
                }
                latch.countDown();
            }
        };
    }

    private Map<String, String> podLabelFrom(Environment environment) {
        ReUsableItems.checkNull(environment);
        return Map.of(ApplicationConstants.MANAGED_BY, ApplicationConstants.SPASHIP,
                ApplicationConstants.WEBSITE, environment.getWebsiteName().toLowerCase(),
                ApplicationConstants.ENVIRONMENT, environment.getName().toLowerCase());
    }

    /**
     * This method applies a command to a target Pod. The command can be one of the following types:
     * CHECK_TARGET_EXISTENCE, DELETE_TARGET, or CREATE_SYMLINK.
     * It first retrieves the Pod using the environment details, then applies the command based on the command type.
     *
     * @param environment       the environment details used to retrieve the Pod
     * @param sourceTargetTuple a tuple containing the source and target details
     * @param commandType       the type of the command to apply
     * @return a CommandExecutionOutput encapsulates the name of the Pod and the console output of the command
     * @throws RuntimeException if there is an error executing the command in the Pod
     */
    public CommandExecutionOutput applyCommand(Environment environment,
                                               Tuple2<String, String> sourceTargetTuple,
                                               CommandExecutionEnums.Command commandType) {

        ReUsableItems.checkNull(environment, sourceTargetTuple, commandType);

        var podLabels = podLabelFrom(environment);
        var ns = environment.getNameSpace();
        var pods = ocClient.pods().inNamespace(ns).withLabels(podLabels).list().getItems();
        var selectedPod = pods.get(0);
        return switch (commandType) {

            case CHECK_TARGET_EXISTENCE -> {
                try {
                    yield checkTargetExistence(selectedPod,
                            sourceTargetTuple.getItem2());
                } catch (CommandExecutionException e) {
                    LOG.error("failed to check target existence", e);
                    //todo :scope of improvement: throw a custom exception
                    throw new RuntimeException(e);
                }
            }
            case DELETE_TARGET -> {
                try {
                    yield deleteTarget(selectedPod, sourceTargetTuple.getItem2());
                } catch (CommandExecutionException e) {
                    LOG.error("failed to delete target", e);
                    //todo :scope of improvement: throw a custom exception
                    throw new RuntimeException(e);
                }catch (IllegalArgumentException e){
                     LOG.error("failed to delete target", e);
                    throw new IllegalArgumentException(e);
                }
            }
            case CREATE_SYMLINK -> {
            try {
                    yield createSymlink(selectedPod, sourceTargetTuple);
                } catch (CommandExecutionException e) {
                    LOG.error("failed to create target", e);
                    throw new RuntimeException(e);
                } catch (IllegalArgumentException e){
                    LOG.error("failed to create target", e);
                    throw new IllegalArgumentException(e);
                }
            }
        };
    }


    /**
     * This method deletes a target in a given Pod. The target can be a file or a directory.
     * It first determines the type of the target (file or directory) by calling the getTargetType method.
     * Then, it constructs a shell command for deleting the target, which is then executed in the Pod.
     * The result of the command execution is encapsulated in a CommandExecutionOutput object and returned.
     *
     * @param targetPod the Pod in which to delete the target
     * @param target    the target to delete
     * @return a CommandExecutionOutput encapsulates the name of the Pod and the console output of the command
     * @throws CommandExecutionException if there is an error executing the command in the Pod
     */
    private CommandExecutionOutput deleteTarget(Pod targetPod, String target) throws CommandExecutionException {
        target = (BASE_HTTP_DIR.concat("/").concat(target));
        var targetType = getTargetType(targetPod, target);
        LOG.debug("computed target type is {}", targetType);
        if (targetType == null) {
            throw new IllegalArgumentException("Invalid target type");
        }
        return switch (targetType) {
            case DIRECTORY -> {
                var command = new String[]{"sh", "-c", "rm -rf " + target};
                var output = executeCommandInContainer(targetPod, command);
                yield new CommandExecutionOutput(targetPod.getMetadata().getName(), output);
            }
            case FILE -> {
                var command = new String[]{"sh", "-c", "rm -f " + target};
                var output = executeCommandInContainer(targetPod, command);
                yield new CommandExecutionOutput(targetPod.getMetadata().getName(), output);
            }
            case BROKEN_SYMLINK -> {
                var command = new String[]{"sh", "-c", "rm -rf " + target};
                var output = executeCommandInContainer(targetPod, command);
                yield new CommandExecutionOutput(targetPod.getMetadata().getName(), output);
            }
            case UNKNOWN -> throw new IllegalArgumentException("Invalid target type: UNKNOWN");
        };
    }

    /**
     * This method checks the existence of a target at a given location in a Pod.
     * It first constructs two shell commands, one for checking if the target is a directory and another for checking if it's a file.
     * These commands are then executed in the Pod.
     * If the target exists and is a directory, it returns a CommandExecutionOutput with the message "dir EXISTS".
     * If the target exists and is a file, it returns a CommandExecutionOutput with the message "file EXISTS".
     * If the target does not exist, it returns a CommandExecutionOutput with the message "DOES_NOT_EXIST".
     *
     * @param targetPod      the Pod in which to check for the target
     * @param targetLocation the location of the target to check
     * @return a CommandExecutionOutput encapsulates the name of the Pod and the console output of the command
     * @throws CommandExecutionException if there is an error executing the command in the Pod
     */
    private CommandExecutionOutput checkTargetExistence(Pod targetPod, String targetLocation)
            throws CommandExecutionException {
        var location = sanitizeInput(BASE_HTTP_DIR.concat("/").concat(targetLocation));
        var dirCommand = buildExistenceCheckCommand("d", location);
        var fileCommand = buildExistenceCheckCommand("f", location);
        var dirOutput = executeCommandInContainer(targetPod, dirCommand).trim();
        var fileOutput = executeCommandInContainer(targetPod, fileCommand).trim();
        LOG.debug("dirOutput is {} and fileOutput is {}", dirOutput, fileOutput);


        String fileExistsString = ("file ".concat(CommandExecutionEnums.Existence.EXISTS.toString())).trim();
        String dirExistsString = ("dir ".concat(CommandExecutionEnums.Existence.EXISTS.toString())).trim();

        LOG.debug("comparing condition for fileExists is {} comparing with {}",
                fileExistsString, fileOutput);

        LOG.debug("comparing condition for dirExists is {} comparing with {}",
                dirExistsString, dirOutput);

        boolean dirExists = dirExistsString.equals(dirOutput);
        LOG.debug("dirExists expression boolean eval is  {}", dirExists);
        boolean fileExists = fileExistsString.equals(fileOutput);
        LOG.debug("fileExists expression boolean eval is  {}", fileExists);

        if (dirExists) {
            return new CommandExecutionOutput(targetPod.getMetadata().getName()
                    , "dir ".concat(CommandExecutionEnums.Existence.EXISTS.toString())
            );
        } else if (fileExists) {
            return new CommandExecutionOutput(targetPod.getMetadata().getName(),
                    "file ".concat(CommandExecutionEnums.Existence.EXISTS.toString())
            );
        } else {
            return new CommandExecutionOutput(targetPod.getMetadata().getName(),
                    CommandExecutionEnums.Existence.DOES_NOT_EXIST.toString());
        }
    }

    /**
     * This method creates a symbolic link (symlink) in a given Pod.
     * It first constructs a shell command for creating the symlink using the source and target details.
     * The command is then executed in the Pod.
     * The result of the command execution is encapsulated in a CommandExecutionOutput object and returned.
     *
     * @param targetPod         the Pod in which to create the symlink
     * @param sourceTargetTuple a tuple containing the source and target details for the symlink
     * @return a CommandExecutionOutput encapsulates the name of the Pod and the console output of the command
     * @throws RuntimeException if there is an error executing the command in the Pod
     */
    private CommandExecutionOutput createSymlink(Pod targetPod,
                                                 Tuple2<String, String> sourceTargetTuple) throws CommandExecutionException {
        var targetType = getTargetType(targetPod, BASE_HTTP_DIR.concat("/").concat(sourceTargetTuple.getItem2()));
        LOG.info("Target type is {}", targetType);
        if (targetType.equals(CommandExecutionEnums.TargetType.DIRECTORY)) { throw new IllegalArgumentException(sourceTargetTuple.getItem2()+" already exists as a directory. Please provide a valid target."); }
        var command = buildSymbolicLinkCommand(sourceTargetTuple.getItem1(), sourceTargetTuple.getItem2());
        try {
            return new CommandExecutionOutput(targetPod.getMetadata().getName(),
                    executeCommandInContainer(targetPod, command));
        } catch (CommandExecutionException e) {
            //todo :scope of improvement: throw a custom exception or CommandExecutionException
            throw new RuntimeException(e);
        }

    }

    /**
     * This method determines the type of a target in a given Pod. The target can be a file, a directory, or unknown.
     * It constructs a shell command to check if the target is a directory or a file, and then executes this command in the Pod.
     * The output of the command is then used to determine the type of the target.
     *
     * @param targetPod the Pod in which to check the target
     * @param target    the target to check
     * @return the type of the target as a CommandExecutionEnums.TargetType enum value
     * @throws CommandExecutionException if there is an error executing the command in the Pod
     */
    private CommandExecutionEnums.TargetType getTargetType(Pod targetPod, String target)  
            throws CommandExecutionException {
        var command = new String[]{"sh", "-c", "if [ -d " + target + " ]; " +
                "then echo " + CommandExecutionEnums.TargetType.DIRECTORY +
                "; elif [ -f " + target + " ]; then echo " + CommandExecutionEnums.TargetType.FILE +
                "; elif [ -L " + target + " ] && ! [ -e " + target + " ]; then " + "echo " + CommandExecutionEnums.TargetType.BROKEN_SYMLINK +
                "; else echo " + CommandExecutionEnums.TargetType.UNKNOWN + "; fi"};
        LOG.debug("command to be executed for checking target type is {}", Arrays.toString(command));
        var output = executeCommandInContainer(targetPod, command).trim();
        LOG.debug("output of the command is {}", output);
        return CommandExecutionEnums.TargetType.valueOf(output.trim().toUpperCase());
    }

    private String sanitizeInput(String input) {
        //todo Implement input sanitization here
        return input;
    }

    private String[] buildExistenceCheckCommand(String type, String location) {
        String existenceMessage = CommandExecutionEnums.Existence.EXISTS.toString();
        if ("d".equals(type)) {
            existenceMessage = "dir ".concat(existenceMessage);
        } else if ("f".equals(type)) {
            existenceMessage = "file ".concat(existenceMessage);
        }
        String command = String.format("if [ -%s \"%s\" ]; then echo \"%s\"; else echo \"%s\"; fi", type,
                location, existenceMessage, CommandExecutionEnums.Existence.DOES_NOT_EXIST);
        LOG.debug("command to be executed {}", command);
        return new String[]{"sh", "-c", command};
    }

    private String[] buildSymbolicLinkCommand(String source, String target) {
        ReUsableItems.checkNull(source, target);
        source = (BASE_HTTP_DIR.concat("/").concat(source));
        target = (BASE_HTTP_DIR.concat("/").concat(target));
        LOG.debug("creating a symlink of source {} to {}", source, target);
        String command = "rm -f " + target + "; ln -s " + source + " " + target;
        LOG.debug("command to be executed {}", command);
        return new String[]{"sh", "-c", command};
    }

    private String executeCommandInContainer(Pod httpdPod, String[] command) throws CommandExecutionException {
        ReUsableItems.checkNull(httpdPod, command);
        LOG.info("command to be executed {}", Arrays.toString(command));
        CountDownLatch latch = new CountDownLatch(1);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ExecWatch ignored = ocClient.pods().inNamespace(httpdPod.getMetadata().getNamespace())
                .withName(httpdPod.getMetadata().getName())
                .inContainer(CONTAINER_NAME).readingInput(System.in). //TODO replace the deprecated method
                // with the new method
                        writingOutput(outputStream).writingError(System.err).withTTY()
                .usingListener(newInstance(latch)).exec(command)) {
            latch.await();
        } catch (Exception e) {
            latch.countDown();
            throw new CommandExecutionException("Error while executing command in container", e);
        }
        return outputStream.toString();
    }
}

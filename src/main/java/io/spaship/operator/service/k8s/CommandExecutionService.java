package io.spaship.operator.service.k8s;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.openshift.client.OpenShiftClient;
import io.smallrye.mutiny.tuples.Tuple2;
import io.spaship.operator.exception.CommandExecutionException;
import io.spaship.operator.type.ApplicationConstants;
import io.spaship.operator.type.Environment;
import io.spaship.operator.util.ReUsableItems;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Named;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

@ApplicationScoped
public class CommandExecutionService {


    // todo :scope of improvement: read these two vars from the config map
    private static final String CONTAINER_NAME = "httpd-server";
    private static final String BASE_HTTP_DIR = "/var/www/html";


    private final OpenShiftClient ocClient;
    private static final Logger LOG = LoggerFactory.getLogger(CommandExecutionService.class);

    public CommandExecutionService(@Named("default") OpenShiftClient ocClient) {
        this.ocClient = ocClient;
    }


    public boolean createSymlink(Environment environment,Tuple2<String, String> sourceTargetTuple) {
        boolean success = false;
        ReUsableItems.checkNull(environment,sourceTargetTuple);
        try{
            execute(podLabelFrom(environment), environment.getNameSpace(), sourceTargetTuple);
            success = true;
        }catch(Exception e){
            LOG.error("failed to create symlink",e);
        }
        return success;
    }

    private Map<String,String> podLabelFrom(Environment environment) {
        ReUsableItems.checkNull(environment);
        return Map.of(ApplicationConstants.MANAGED_BY, ApplicationConstants.SPASHIP,
                ApplicationConstants.WEBSITE, environment.getWebsiteName().toLowerCase(),
                ApplicationConstants.ENVIRONMENT, environment.getName().toLowerCase());
    }

    private void execute(Map<String, String> podLabel, String namespace,
                        Tuple2<String, String> sourceTargetTuple) {
        ReUsableItems.checkNull(podLabel,namespace, sourceTargetTuple);
        // even changing in one of the pod will reflect in all the pods as long as the volume is shared
        var pod = ocClient.pods().inNamespace(namespace).withLabels(podLabel).list().getItems();
        if (pod.isEmpty()) {
            throw new RuntimeException("No pod found for label " + podLabel);
        }

        // TODO :scope of improvement: add support for multiple commands
        var command  = symbolicLinkCommand(sourceTargetTuple.getItem1(), sourceTargetTuple.getItem2());

        pod.forEach(p -> {
            try {
                executeCommandInContainer(p, command);
            } catch (CommandExecutionException e) {
                throw new RuntimeException(e);
            }
        });
    }

   // todo :must do: 1. check both source and destination exists 2. Validate and sanitize the
   //  command
    private String[] symbolicLinkCommand(String source, String target) {
        ReUsableItems.checkNull(source,target);
        source = (BASE_HTTP_DIR.concat("/").concat(source));
        target = (BASE_HTTP_DIR.concat("/").concat(target));
        LOG.debug("creating a symlink of source {} to {}",source,target);
        LOG.debug("command to be executed [ln] [-s] [{}] [{}]",source,target);
        return new String[]{"ln", "-s", source, target};
    }

    private  void executeCommandInContainer(Pod httpdPod, String[] command) throws CommandExecutionException {
        ReUsableItems.checkNull(httpdPod,command);
        CountDownLatch latch = new CountDownLatch(1);
        try (ExecWatch ignored = ocClient.pods().inNamespace(httpdPod.getMetadata().getNamespace())
                .withName(httpdPod.getMetadata().getName())
                .inContainer(CONTAINER_NAME).readingInput(System.in). //TODO replace the deprecated method
                // with the new method
                writingOutput(System.out).writingError(System.err).withTTY()
                .usingListener(newInstance(latch)).exec(command)) {
            latch.await();
        } catch (Exception e) {
            latch.countDown();
            throw new CommandExecutionException("Error while executing command in container", e);
        }
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
}
